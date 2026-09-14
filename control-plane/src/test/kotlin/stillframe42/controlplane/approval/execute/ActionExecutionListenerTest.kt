package stillframe42.controlplane.approval.execute

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.context.ApplicationEventPublisher
import stillframe42.controlplane.messaging.EventPublisher
import stillframe42.controlplane.messaging.OpsTopics
import stillframe42.controlplane.approval.model.ActionApprovalRequest
import stillframe42.controlplane.approval.model.ActionExecution
import stillframe42.controlplane.approval.model.ApprovalCard
import stillframe42.controlplane.approval.model.ApprovalStatus
import stillframe42.controlplane.approval.model.PendingApproval
import stillframe42.controlplane.approval.model.SlackMessageRef
import stillframe42.controlplane.approval.notify.ApprovalMessenger
import stillframe42.controlplane.approval.repository.ActionApprovalRepository
import stillframe42.controlplane.approval.service.ActionApprovalService
import stillframe42.controlplane.approval.service.ApprovalDecided

/**
 * 승인 확정 → 실행 → 발행 연결 계약 (ADR-0005 "실행 후 발행") — approved 만 실행하고,
 * 실행 성패와 무관하게 decisions 를 발행하며, 결과를 스레드로 회신한다는 규약을 검증한다.
 */
class ActionExecutionListenerTest {

    private class FakeRepository(private val card: ApprovalCard?) : ActionApprovalRepository {
        val executed = mutableListOf<Triple<String, Instant, String>>()
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
        override fun markExecuted(incidentId: String, executedAt: Instant, note: String): Boolean {
            executed += Triple(incidentId, executedAt, note)
            return true
        }
    }

    private class RecordingExecutor(private val ok: Boolean = true) : ActionExecutor {
        val requested = mutableListOf<String>()
        override fun execute(action: String): ActionExecution {
            requested += action
            return ActionExecution(action, ok, if (ok) "완료" else "실패")
        }
    }

    private class RecordingPublisher : EventPublisher {
        val published = mutableListOf<Triple<String, String?, String>>()
        override fun publish(topic: String, key: String?, payload: String): Boolean {
            published += Triple(topic, key, payload)
            return true
        }
    }

    private class RecordingMessenger : ApprovalMessenger {
        val threadReplies = mutableListOf<Pair<SlackMessageRef, String>>()
        override fun sendApprovalRequest(request: ActionApprovalRequest): SlackMessageRef? = null
        override fun closeApprovalRequest(
            message: SlackMessageRef,
            request: ActionApprovalRequest,
            status: String,
            decidedBy: String,
            decidedAt: Instant,
        ) = Unit

        override fun postThreadReply(message: SlackMessageRef, text: String) {
            threadReplies += message to text
        }
    }

    private val incidentId = "inc-error-rate-surge-20260804010000-ab12cd"
    private val slackMessage = SlackMessageRef("C0123", "1722672000.000100")

    private fun request(actions: List<String>) = ActionApprovalRequest(
        incidentId = incidentId,
        actionType = actions.firstOrNull { it != "NOTIFY_ONLY" } ?: "NOTIFY_ONLY",
        riskLevel = "P2",
        confidence = 0.95,
        requestedAt = Instant.parse("2026-08-04T01:00:00Z"),
        raw = "{}",
        actions = actions,
    )

    private fun decided(status: String = ApprovalStatus.APPROVED) = ApprovalDecided(
        incidentId,
        status,
        decidedBy = "U0123ABC",
        decidedAt = Instant.parse("2026-08-04T01:05:00Z"),
    )

    private data class Fixture(
        val listener: ActionExecutionListener,
        val repository: FakeRepository,
        val executor: RecordingExecutor,
        val publisher: RecordingPublisher,
        val messenger: RecordingMessenger,
    )

    private fun fixture(
        card: ApprovalCard?,
        executor: RecordingExecutor = RecordingExecutor(),
    ): Fixture {
        val repository = FakeRepository(card)
        val publisher = RecordingPublisher()
        val messenger = RecordingMessenger()
        val service = ActionApprovalService(repository, publisher, ApplicationEventPublisher { })
        return Fixture(
            ActionExecutionListener(repository, executor, service, messenger),
            repository,
            executor,
            publisher,
            messenger,
        )
    }

    @Test
    fun `승인 결정은 조치를 페이로드 순서대로 실행하고 결과를 담아 발행한다`() {
        val f = fixture(ApprovalCard(request(listOf("CIRCUIT_BREAK", "RESTART_APP")), slackMessage))

        f.listener.onDecided(decided())

        assertEquals(listOf("CIRCUIT_BREAK", "RESTART_APP"), f.executor.requested)
        assertEquals(incidentId, f.repository.executed.single().first)
        val (topic, key, payload) = f.publisher.published.single()
        assertEquals(OpsTopics.ACTIONS_DECISIONS, topic)
        assertEquals(incidentId, key)
        assertTrue(payload.contains("\"execution\""))
        assertTrue(payload.contains("RESTART_APP"))
        assertTrue(f.messenger.threadReplies.single().second.contains("조치 실행 결과"))
    }

    @Test
    fun `거부 결정은 아무것도 하지 않는다 - decisions 는 decide 트랜잭션이 이미 발행`() {
        val f = fixture(ApprovalCard(request(listOf("RESTART_APP")), slackMessage))

        f.listener.onDecided(decided(ApprovalStatus.REJECTED))

        assertEquals(0, f.executor.requested.size)
        assertEquals(0, f.publisher.published.size)
        assertEquals(0, f.messenger.threadReplies.size)
    }

    @Test
    fun `NOTIFY_ONLY 는 실행 대상에서 제외된다`() {
        val f = fixture(ApprovalCard(request(listOf("CIRCUIT_BREAK", "NOTIFY_ONLY")), slackMessage))

        f.listener.onDecided(decided())

        assertEquals(listOf("CIRCUIT_BREAK"), f.executor.requested)
    }

    @Test
    fun `실행이 실패해도 decisions 는 발행한다 - 재개 신호가 우선, 성패는 페이로드가 전달`() {
        val f = fixture(
            ApprovalCard(request(listOf("RESTART_APP")), slackMessage),
            executor = RecordingExecutor(ok = false),
        )

        f.listener.onDecided(decided())

        val payload = f.publisher.published.single().third
        assertTrue(payload.contains("\"ok\":false"))
        assertTrue(f.messenger.threadReplies.single().second.contains(":x:"))
    }

    @Test
    fun `승인 행이 없어도 발행은 한다 - 실행 없이 재개 신호만`() {
        val f = fixture(card = null)

        f.listener.onDecided(decided())

        assertEquals(0, f.executor.requested.size)
        assertEquals(0, f.repository.executed.size)
        assertEquals(1, f.publisher.published.size)
        assertEquals(0, f.messenger.threadReplies.size)
    }
}
