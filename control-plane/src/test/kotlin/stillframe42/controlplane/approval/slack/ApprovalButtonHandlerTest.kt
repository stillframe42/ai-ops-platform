package stillframe42.controlplane.approval.slack

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.context.ApplicationEventPublisher
import stillframe42.controlplane.messaging.EventPublisher
import stillframe42.controlplane.approval.model.ActionApprovalRequest
import stillframe42.controlplane.approval.model.ApprovalCard
import stillframe42.controlplane.approval.model.ApprovalStatus
import stillframe42.controlplane.approval.model.PendingApproval
import stillframe42.controlplane.approval.model.SlackMessageRef
import stillframe42.controlplane.approval.notify.ApprovalMessageFactory
import stillframe42.controlplane.approval.repository.ActionApprovalRepository
import stillframe42.controlplane.approval.service.ActionApprovalService

/**
 * 버튼 → 결정 매핑 계약 — 실 서비스 경유 + fake 저장소·발행기 (컨트롤러 테스트와 같은 관례).
 * 정상 결정은 즉답 없음(스레드 회신은 ApprovalDecided 리스너 몫), 예외 상황만 즉답 텍스트.
 */
class ApprovalButtonHandlerTest {

    private class FakeRepository(
        private val transitioned: Boolean = true,
        private val latestStatus: String? = null,
    ) : ActionApprovalRepository {
        val decided = mutableListOf<Triple<String, String, String>>()

        override fun insertPendingIfAbsent(request: ActionApprovalRequest): Boolean = true
        override fun markDecided(
            incidentId: String,
            status: String,
            decidedBy: String,
            decidedAt: Instant,
        ): Boolean {
            decided += Triple(incidentId, status, decidedBy)
            return transitioned
        }

        override fun findLatestStatus(incidentId: String): String? = latestStatus
        override fun recordSlackMessage(incidentId: String, message: SlackMessageRef): Boolean = false
        override fun markReminded(incidentId: String, remindedAt: Instant): Boolean = false
        override fun findPendingRequestedBefore(cutoff: Instant): List<PendingApproval> = emptyList()
        override fun findLatestCard(incidentId: String): ApprovalCard? = null
        override fun markExecuted(incidentId: String, executedAt: Instant, note: String): Boolean = false
    }

    private val incidentId = "inc-error-rate-surge-20260803120000-ab12cd"

    private fun handler(
        repository: FakeRepository = FakeRepository(),
        publishAccepted: Boolean = true,
    ) = ApprovalButtonHandler(
        ActionApprovalService(
            repository,
            EventPublisher { _, _, _ -> publishAccepted },
            ApplicationEventPublisher { },
        ),
    )

    @Test
    fun `승인 버튼은 approved 로 결정하고 클릭한 사용자가 주체다`() {
        val repository = FakeRepository()

        val reply = handler(repository)
            .handle(ApprovalMessageFactory.ACTION_APPROVE, incidentId, slackUserId = "U0123ABC")

        assertNull(reply, "정상 결정의 회신은 ApprovalDecided 리스너 몫 — 즉답 없음")
        assertEquals(Triple(incidentId, ApprovalStatus.APPROVED, "U0123ABC"), repository.decided.single())
    }

    @Test
    fun `거부 버튼은 rejected 로 결정한다`() {
        val repository = FakeRepository()

        handler(repository).handle(ApprovalMessageFactory.ACTION_REJECT, incidentId, "U0123ABC")

        assertEquals(ApprovalStatus.REJECTED, repository.decided.single().second)
    }

    @Test
    fun `이미 결정된 요청 클릭은 종결 상태를 즉답한다`() {
        val reply = handler(FakeRepository(transitioned = false, latestStatus = ApprovalStatus.REJECTED))
            .handle(ApprovalMessageFactory.ACTION_APPROVE, incidentId, "U0123ABC")

        assertTrue(assertNotNull(reply).contains(ApprovalStatus.REJECTED))
    }

    @Test
    fun `모르는 인시던트 클릭은 안내를 즉답한다`() {
        val reply = handler(FakeRepository(transitioned = false, latestStatus = null))
            .handle(ApprovalMessageFactory.ACTION_APPROVE, incidentId, "U0123ABC")

        assertTrue(assertNotNull(reply).contains("찾을 수 없습니다"))
    }

    @Test
    fun `실행 없는 결정의 발행 접수 실패는 재시도 안내를 즉답한다 - 전이는 롤백`() {
        // approved 는 즉시 발행하지 않으므로(실행 후 발행, ADR-0005) 동기 발행 실패 경로는 거부 쪽
        val reply = handler(publishAccepted = false)
            .handle(ApprovalMessageFactory.ACTION_REJECT, incidentId, "U0123ABC")

        assertTrue(assertNotNull(reply).contains("다시"))
    }

    @Test
    fun `모르는 action_id 는 무시한다`() {
        val repository = FakeRepository()

        val reply = handler(repository).handle("unrelated-action", incidentId, "U0123ABC")

        assertNull(reply)
        assertEquals(0, repository.decided.size)
    }
}
