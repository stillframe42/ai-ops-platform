package stillframe42.controlplane.approval.notify

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import stillframe42.controlplane.approval.model.ActionApprovalRequest
import stillframe42.controlplane.approval.model.ApprovalCard
import stillframe42.controlplane.approval.model.ApprovalStatus
import stillframe42.controlplane.approval.model.PendingApproval
import stillframe42.controlplane.approval.model.SlackMessageRef
import stillframe42.controlplane.approval.repository.ActionApprovalRepository
import stillframe42.controlplane.approval.service.ApprovalDecided

/**
 * 결정 이벤트 → 카드 마감·스레드 회신 연결 계약 — 카드 좌표가 없으면(발송 생략 건) 조용히
 * 마친다. 어느 입력 경로의 결정이든 이 리스너 하나가 출력을 담당한다는 규약이 핵심 (ADR-0006).
 */
class ApprovalDecidedListenerTest {

    private class StubRepository(private val card: ApprovalCard?) : ActionApprovalRepository {
        override fun insertPendingIfAbsent(request: ActionApprovalRequest): Boolean = true
        override fun markDecided(
            incidentId: String,
            status: String,
            decidedBy: String,
            decidedAt: Instant,
        ): Boolean = true

        override fun findLatestStatus(incidentId: String): String? = null
        override fun recordSlackMessage(incidentId: String, message: SlackMessageRef): Boolean = false
        override fun markReminded(incidentId: String, remindedAt: Instant): Boolean = false
        override fun findPendingRequestedBefore(cutoff: Instant): List<PendingApproval> = emptyList()
        override fun findLatestCard(incidentId: String): ApprovalCard? = card
    }

    private class RecordingMessenger : ApprovalMessenger {
        val closed = mutableListOf<Triple<SlackMessageRef, String, String>>()
        val threadReplies = mutableListOf<Pair<SlackMessageRef, String>>()

        override fun sendApprovalRequest(request: ActionApprovalRequest): SlackMessageRef? = null

        override fun closeApprovalRequest(
            message: SlackMessageRef,
            request: ActionApprovalRequest,
            status: String,
            decidedBy: String,
            decidedAt: Instant,
        ) {
            closed += Triple(message, status, decidedBy)
        }

        override fun postThreadReply(message: SlackMessageRef, text: String) {
            threadReplies += message to text
        }
    }

    private val incidentId = "inc-error-rate-surge-20260803120000-ab12cd"

    private val request = ActionApprovalRequest(
        incidentId = incidentId,
        actionType = "CIRCUIT_BREAK",
        riskLevel = "P2",
        confidence = 0.95,
        requestedAt = Instant.parse("2026-08-03T12:00:00Z"),
        raw = "{}",
    )

    private val decided = ApprovalDecided(
        incidentId,
        ApprovalStatus.APPROVED,
        decidedBy = "U0123ABC",
        decidedAt = Instant.parse("2026-08-03T12:10:00Z"),
    )

    @Test
    fun `카드 좌표가 있으면 마감하고 스레드로 결과를 회신한다`() {
        val message = SlackMessageRef("C0123", "1722672000.000100")
        val messenger = RecordingMessenger()

        ApprovalDecidedListener(StubRepository(ApprovalCard(request, message)), messenger)
            .onDecided(decided)

        assertEquals(Triple(message, ApprovalStatus.APPROVED, "U0123ABC"), messenger.closed.single())
        assertTrue(messenger.threadReplies.single().second.contains("승인"))
    }

    @Test
    fun `카드 좌표가 없으면 아무것도 보내지 않는다 - Slack 미발송 건`() {
        val messenger = RecordingMessenger()

        ApprovalDecidedListener(StubRepository(ApprovalCard(request, slackMessage = null)), messenger)
            .onDecided(decided)

        assertEquals(0, messenger.closed.size)
        assertEquals(0, messenger.threadReplies.size)
    }

    @Test
    fun `승인 행 자체가 없으면 아무것도 보내지 않는다`() {
        val messenger = RecordingMessenger()

        ApprovalDecidedListener(StubRepository(card = null), messenger).onDecided(decided)

        assertEquals(0, messenger.closed.size)
    }
}
