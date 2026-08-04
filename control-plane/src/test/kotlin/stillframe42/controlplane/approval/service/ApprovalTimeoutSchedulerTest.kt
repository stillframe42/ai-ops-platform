package stillframe42.controlplane.approval.service

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.context.ApplicationEventPublisher
import stillframe42.controlplane.alert.event.EventPublisher
import stillframe42.controlplane.approval.model.ActionApprovalRequest
import stillframe42.controlplane.approval.model.ApprovalCard
import stillframe42.controlplane.approval.model.ApprovalStatus
import stillframe42.controlplane.approval.model.PendingApproval
import stillframe42.controlplane.approval.model.SlackMessageRef
import stillframe42.controlplane.approval.notify.ApprovalMessenger
import stillframe42.controlplane.approval.repository.ActionApprovalRepository

/**
 * 타임아웃 스캔 계약 (ADR-0006) — 기준 시각을 sweepAt 인자로 넘겨 시계 대역 없이 경계를
 * 검증한다. 만료는 decide 경로 재사용(decisions 발행 포함), 재알림은 1회 + 카드 좌표가
 * 있을 때만 회신이라는 규약이 핵심.
 */
class ApprovalTimeoutSchedulerTest {

    private class StubRepository(
        private val pending: List<PendingApproval>,
        private val transitioned: Boolean = true,
    ) : ActionApprovalRepository {
        val decided = mutableListOf<Triple<String, String, String>>()
        val reminded = mutableListOf<String>()

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

        override fun findLatestStatus(incidentId: String): String? = null
        override fun recordSlackMessage(incidentId: String, message: SlackMessageRef): Boolean = false

        override fun markReminded(incidentId: String, remindedAt: Instant): Boolean {
            reminded += incidentId
            return true
        }

        override fun findPendingRequestedBefore(cutoff: Instant): List<PendingApproval> =
            pending.filter { it.requestedAt.isBefore(cutoff) }

        override fun findLatestCard(incidentId: String): ApprovalCard? = null
        override fun markExecuted(incidentId: String, executedAt: Instant, note: String): Boolean = false
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

    private class RecordingPublisher(private val accepted: Boolean = true) : EventPublisher {
        val published = mutableListOf<Triple<String, String?, String>>()
        override fun publish(topic: String, key: String?, payload: String): Boolean {
            published += Triple(topic, key, payload)
            return accepted
        }
    }

    private val now = Instant.parse("2026-08-03T12:00:00Z")
    private val message = SlackMessageRef("C0123", "1722672000.000100")

    private fun pending(
        incidentId: String,
        requestedAgo: Duration,
        remindedAt: Instant? = null,
        slackMessage: SlackMessageRef? = message,
    ) = PendingApproval(incidentId, now.minus(requestedAgo), remindedAt, slackMessage)

    private fun sweep(
        pending: List<PendingApproval>,
        repository: StubRepository = StubRepository(pending),
        publisher: RecordingPublisher = RecordingPublisher(),
        messenger: RecordingMessenger = RecordingMessenger(),
    ): Triple<StubRepository, RecordingPublisher, RecordingMessenger> {
        val service = ActionApprovalService(repository, publisher, ApplicationEventPublisher { })
        ApprovalTimeoutScheduler(
            service,
            repository,
            messenger,
            Duration.ofMinutes(30),
            Duration.ofMinutes(60),
        ).sweepAt(now)
        return Triple(repository, publisher, messenger)
    }

    @Test
    fun `만료 경과 pending 은 expired 로 전이하고 주체는 system 이다`() {
        val (repository, publisher, _) = sweep(listOf(pending("inc-1", requestedAgo = Duration.ofMinutes(61))))

        assertEquals(
            Triple("inc-1", ApprovalStatus.EXPIRED, ApprovalTimeoutScheduler.TIMEOUT_DECIDER),
            repository.decided.single(),
        )
        // decide 경로 재사용 — 만료도 decisions 발행으로 agent 대기를 종결시킨다
        assertTrue(publisher.published.single().third.contains("\"expired\""))
    }

    @Test
    fun `재알림 구간 pending 은 표식 후 스레드로 재알림한다`() {
        val (repository, publisher, messenger) =
            sweep(listOf(pending("inc-1", requestedAgo = Duration.ofMinutes(31))))

        assertEquals(listOf("inc-1"), repository.reminded)
        assertTrue(messenger.threadReplies.single().second.contains("30분"))
        assertEquals(0, repository.decided.size)
        assertEquals(0, publisher.published.size)
    }

    @Test
    fun `재알림 표식이 있으면 반복하지 않는다 - 1회 규약`() {
        val (repository, _, messenger) = sweep(
            listOf(pending("inc-1", requestedAgo = Duration.ofMinutes(45), remindedAt = now.minusSeconds(600))),
        )

        assertEquals(0, repository.reminded.size)
        assertEquals(0, messenger.threadReplies.size)
    }

    @Test
    fun `카드 좌표가 없으면 표식만 남긴다 - 만료 안전망은 Slack 무관`() {
        val (repository, _, messenger) = sweep(
            listOf(pending("inc-1", requestedAgo = Duration.ofMinutes(31), slackMessage = null)),
        )

        assertEquals(listOf("inc-1"), repository.reminded)
        assertEquals(0, messenger.threadReplies.size)
    }

    @Test
    fun `재알림 시각 전 pending 은 스캔 대상이 아니다`() {
        val (repository, _, messenger) = sweep(listOf(pending("inc-1", requestedAgo = Duration.ofMinutes(10))))

        assertEquals(0, repository.reminded.size)
        assertEquals(0, repository.decided.size)
        assertEquals(0, messenger.threadReplies.size)
    }

    @Test
    fun `한 건의 발행 실패가 나머지 만료 처리를 막지 않는다`() {
        val stale = listOf(
            pending("inc-1", requestedAgo = Duration.ofMinutes(61)),
            pending("inc-2", requestedAgo = Duration.ofMinutes(90)),
        )
        val repository = StubRepository(stale)

        sweep(stale, repository, publisher = RecordingPublisher(accepted = false))

        // 발행 접수 실패는 항목별로 삼켜지고 다음 항목이 처리된다 — 다음 주기가 재시도
        assertEquals(listOf("inc-1", "inc-2"), repository.decided.map { it.first })
    }
}
