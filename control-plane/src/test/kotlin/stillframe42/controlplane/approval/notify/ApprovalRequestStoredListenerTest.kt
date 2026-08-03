package stillframe42.controlplane.approval.notify

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.context.ApplicationEventPublisher
import stillframe42.controlplane.alert.event.EventPublisher
import stillframe42.controlplane.approval.model.ActionApprovalRequest
import stillframe42.controlplane.approval.model.ApprovalCard
import stillframe42.controlplane.approval.model.PendingApproval
import stillframe42.controlplane.approval.model.SlackMessageRef
import stillframe42.controlplane.approval.repository.ActionApprovalRepository
import stillframe42.controlplane.approval.service.ActionApprovalService
import stillframe42.controlplane.approval.service.ApprovalRequestStored

/**
 * 저장 이벤트 → 카드 발송 → 좌표 기록 연결 계약 — 발송 실패(null)면 좌표 기록도 없다.
 * @Async·AFTER_COMMIT 배선은 스프링 몫이라 여기서는 메서드 계약만 검증한다
 * (IncidentReportStoredListener 와 같은 경계).
 */
class ApprovalRequestStoredListenerTest {

    private class RecordingRepository : ActionApprovalRepository {
        val recordedMessages = mutableListOf<Pair<String, SlackMessageRef>>()

        override fun insertPendingIfAbsent(request: ActionApprovalRequest): Boolean = true
        override fun markDecided(
            incidentId: String,
            status: String,
            decidedBy: String,
            decidedAt: Instant,
        ): Boolean = true

        override fun findLatestStatus(incidentId: String): String? = null

        override fun recordSlackMessage(incidentId: String, message: SlackMessageRef): Boolean {
            recordedMessages += incidentId to message
            return true
        }

        override fun markReminded(incidentId: String, remindedAt: Instant): Boolean = false
        override fun findPendingRequestedBefore(cutoff: Instant): List<PendingApproval> = emptyList()
        override fun findLatestCard(incidentId: String): ApprovalCard? = null
    }

    private class StubMessenger(private val sendResult: SlackMessageRef?) : ApprovalMessenger {
        override fun sendApprovalRequest(request: ActionApprovalRequest): SlackMessageRef? = sendResult
        override fun closeApprovalRequest(
            message: SlackMessageRef,
            request: ActionApprovalRequest,
            status: String,
            decidedBy: String,
            decidedAt: Instant,
        ) = Unit

        override fun postThreadReply(message: SlackMessageRef, text: String) = Unit
    }

    private val request = ActionApprovalRequest(
        incidentId = "inc-error-rate-surge-20260803120000-ab12cd",
        actionType = "CIRCUIT_BREAK",
        riskLevel = "P2",
        confidence = 0.95,
        requestedAt = Instant.parse("2026-08-03T12:00:00Z"),
        raw = "{}",
    )

    private fun listener(repository: RecordingRepository, sendResult: SlackMessageRef?) =
        ApprovalRequestStoredListener(
            StubMessenger(sendResult),
            ActionApprovalService(repository, EventPublisher { _, _, _ -> true }, ApplicationEventPublisher { }),
        )

    @Test
    fun `발송 성공이면 좌표를 기록한다 - 마감·재알림·회신의 대상`() {
        val repository = RecordingRepository()
        val message = SlackMessageRef("C0123", "1722672000.000100")

        listener(repository, message).onStored(ApprovalRequestStored(request))

        assertEquals(request.incidentId to message, repository.recordedMessages.single())
    }

    @Test
    fun `발송 생략·실패면 좌표 기록도 없다`() {
        val repository = RecordingRepository()

        listener(repository, sendResult = null).onStored(ApprovalRequestStored(request))

        assertEquals(0, repository.recordedMessages.size)
    }
}
