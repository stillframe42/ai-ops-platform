package stillframe42.controlplane.approval.service

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.springframework.context.ApplicationEventPublisher
import stillframe42.controlplane.messaging.EventPublisher
import stillframe42.controlplane.messaging.OpsTopics
import stillframe42.controlplane.approval.model.ActionApprovalRequest
import stillframe42.controlplane.approval.model.ActionExecution
import stillframe42.controlplane.approval.model.ApprovalCard
import stillframe42.controlplane.approval.model.ApprovalDecisionOutcome
import stillframe42.controlplane.approval.model.ApprovalStatus
import stillframe42.controlplane.approval.model.PendingApproval
import stillframe42.controlplane.approval.model.SlackMessageRef
import stillframe42.controlplane.approval.repository.ActionApprovalRepository

/**
 * 단위 테스트 경계 — Kafka·DB 무의존, fake 주입 (IncidentReportService 테스트와 같은 관례).
 * "언제 저장하고, 언제 전이하고, 언제 decisions·앱 이벤트를 발행하는가" 규약만 검증한다.
 */
class ActionApprovalServiceTest {

    private class RecordingRepository(
        private val insertIsNew: Boolean = true,
        private val transitioned: Boolean = true,
        private val latestStatus: String? = null,
    ) : ActionApprovalRepository {
        val inserted = mutableListOf<ActionApprovalRequest>()
        val decided = mutableListOf<Triple<String, String, String>>()
        val recordedMessages = mutableListOf<Pair<String, SlackMessageRef>>()
        val reminded = mutableListOf<String>()

        override fun insertPendingIfAbsent(request: ActionApprovalRequest): Boolean {
            inserted += request
            return insertIsNew
        }

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

        override fun recordSlackMessage(incidentId: String, message: SlackMessageRef): Boolean {
            recordedMessages += incidentId to message
            return true
        }

        override fun markReminded(incidentId: String, remindedAt: Instant): Boolean {
            reminded += incidentId
            return true
        }

        override fun findPendingRequestedBefore(cutoff: Instant): List<PendingApproval> = emptyList()

        override fun findLatestCard(incidentId: String): ApprovalCard? = null

        val executed = mutableListOf<Triple<String, Instant, String>>()
        override fun markExecuted(incidentId: String, executedAt: Instant, note: String): Boolean {
            executed += Triple(incidentId, executedAt, note)
            return true
        }
    }

    private class RecordingPublisher(private val accepted: Boolean = true) : EventPublisher {
        val published = mutableListOf<Triple<String, String?, String>>()
        override fun publish(topic: String, key: String?, payload: String): Boolean {
            published += Triple(topic, key, payload)
            return accepted
        }
    }

    private class RecordingEvents : ApplicationEventPublisher {
        val published = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            published += event
        }
    }

    private val incidentId = "inc-memory-pressure-20260801100000-ab12cd"

    private fun pendingPayload() = """
        {"incident_id": "$incidentId", "scenario": "memory-pressure", "severity": "P1",
         "confidence": 0.9, "actions": ["RESTART_APP"], "rationale": "재시작 필요",
         "requested_at": "2026-08-01T10:00:00+00:00"}
    """.trimIndent()

    private fun service(
        repository: RecordingRepository = RecordingRepository(),
        publisher: RecordingPublisher = RecordingPublisher(),
        events: RecordingEvents = RecordingEvents(),
    ) = ActionApprovalService(repository, publisher, events)

    @Test
    fun `승인 요청 신규 수신은 pending 으로 저장한다`() {
        val repository = RecordingRepository(insertIsNew = true)

        service(repository).ingest(pendingPayload())

        val request = repository.inserted.single()
        assertEquals(incidentId, request.incidentId)
        assertEquals("RESTART_APP", request.actionType)
    }

    @Test
    fun `신규 저장은 ApprovalRequestStored 이벤트를 발행한다 - Slack 카드 발송의 연결점`() {
        val events = RecordingEvents()

        service(events = events).ingest(pendingPayload())

        val stored = assertIs<ApprovalRequestStored>(events.published.single())
        assertEquals(incidentId, stored.request.incidentId)
    }

    @Test
    fun `재수신은 이벤트를 발행하지 않는다 - 승인 카드 중복 방지`() {
        val events = RecordingEvents()

        service(RecordingRepository(insertIsNew = false), events = events).ingest(pendingPayload())

        assertEquals(0, events.published.size)
    }

    @Test
    fun `파싱 불가 페이로드는 저장 없이 건너뛴다 - poison pill 이 커밋을 막지 않는다`() {
        val repository = RecordingRepository()

        service(repository).ingest("not-json{{{")

        assertEquals(0, repository.inserted.size)
    }

    @Test
    fun `승인 결정은 전이만 - decisions 는 조치 실행 후 리스너가 발행한다 (ADR-0005 실행 후 발행)`() {
        val publisher = RecordingPublisher()

        val outcome = service(publisher = publisher)
            .decide(incidentId, ApprovalStatus.APPROVED, decidedBy = "U0123ABC")

        val decided = assertIs<ApprovalDecisionOutcome.Decided>(outcome)
        assertEquals(ApprovalStatus.APPROVED, decided.status)
        assertEquals("U0123ABC", decided.decidedBy)
        assertEquals(0, publisher.published.size)
    }

    @Test
    fun `승인 결정은 ApprovalDecided 이벤트를 발행한다 - 카드 마감의 연결점`() {
        val events = RecordingEvents()

        service(events = events).decide(incidentId, ApprovalStatus.APPROVED, decidedBy = "U0123ABC")

        val decided = assertIs<ApprovalDecided>(events.published.single())
        assertEquals(incidentId, decided.incidentId)
        assertEquals(ApprovalStatus.APPROVED, decided.status)
    }

    @Test
    fun `거부 결정은 rejected 로 전이·발행한다`() {
        val publisher = RecordingPublisher()

        val outcome = service(publisher = publisher)
            .decide(incidentId, ApprovalStatus.REJECTED, decidedBy = "U0123ABC")

        assertEquals(ApprovalStatus.REJECTED, assertIs<ApprovalDecisionOutcome.Decided>(outcome).status)
        assert(publisher.published.single().third.contains("\"rejected\""))
    }

    @Test
    fun `모르는 인시던트 결정은 NotFound - 발행 없음`() {
        val repository = RecordingRepository(transitioned = false, latestStatus = null)
        val publisher = RecordingPublisher()

        val outcome = service(repository, publisher)
            .decide(incidentId, ApprovalStatus.APPROVED, decidedBy = "U0123ABC")

        assertIs<ApprovalDecisionOutcome.NotFound>(outcome)
        assertEquals(0, publisher.published.size)
    }

    @Test
    fun `이미 결정된 승인은 AlreadyDecided - 중복 결정이 발행을 반복하지 않는다`() {
        val repository = RecordingRepository(transitioned = false, latestStatus = ApprovalStatus.APPROVED)
        val publisher = RecordingPublisher()

        val outcome = service(repository, publisher)
            .decide(incidentId, ApprovalStatus.REJECTED, decidedBy = "U9999XYZ")

        assertEquals(
            ApprovalStatus.APPROVED,
            assertIs<ApprovalDecisionOutcome.AlreadyDecided>(outcome).status,
        )
        assertEquals(0, publisher.published.size)
    }

    @Test
    fun `실행 없는 결정의 발행 실패면 예외 - 트랜잭션 롤백으로 전이도 되돌린다`() {
        // 전이만 남고 발행이 유실되면 agent-service 가 영영 승인 대기 — 롤백 후 재시도가 정직하다
        val events = RecordingEvents()
        val failing = service(publisher = RecordingPublisher(accepted = false), events = events)

        assertFailsWith<DecisionPublishFailedException> {
            failing.decide(incidentId, ApprovalStatus.REJECTED, decidedBy = "U0123ABC")
        }
        // 롤백되는 결정의 ApprovalDecided 도 없어야 한다 — 마감 회신이 미결정 카드를 닫으면 안 됨
        assertEquals(0, events.published.size)
    }

    @Test
    fun `만료 결정도 즉시 발행한다 - 실행이 없는 결정은 기존 규약 유지`() {
        val publisher = RecordingPublisher()

        service(publisher = publisher).decide(incidentId, ApprovalStatus.EXPIRED, decidedBy = "system")

        assert(publisher.published.single().third.contains("\"expired\""))
    }

    @Test
    fun `실행 결과 기록은 성패·상세를 note 로 병합해 저장소에 위임한다`() {
        val repository = RecordingRepository()

        service(repository).recordExecution(
            incidentId,
            Instant.parse("2026-08-04T01:00:00Z"),
            listOf(
                ActionExecution("CIRCUIT_BREAK", true, "chaos/reset 호출 완료"),
                ActionExecution("RESTART_APP", true, "운영자 직접 실행 대상", manual = true),
                ActionExecution("SCALE_OUT", false, "미지원 조치"),
            ),
        )

        val (recordedId, _, note) = repository.executed.single()
        assertEquals(incidentId, recordedId)
        assert(note.contains("CIRCUIT_BREAK: 성공"))
        assert(note.contains("RESTART_APP: 수동 안내"))
        assert(note.contains("SCALE_OUT: 실패"))
    }

    @Test
    fun `실행 후 발행은 실행 결과를 페이로드에 담는다`() {
        val publisher = RecordingPublisher()
        val decidedAt = Instant.parse("2026-08-04T01:00:00Z")

        val accepted = service(publisher = publisher).publishExecutedDecision(
            ApprovalDecided(incidentId, ApprovalStatus.APPROVED, "U0123ABC", decidedAt),
            listOf(ActionExecution("CIRCUIT_BREAK", true, "chaos/reset 호출 완료")),
            decidedAt.plusSeconds(2),
        )

        assert(accepted)
        val (topic, key, payload) = publisher.published.single()
        assertEquals(OpsTopics.ACTIONS_DECISIONS, topic)
        assertEquals(incidentId, key)
        assert(payload.contains("\"execution\"")) { "실행 결과가 페이로드에 실려야 한다: $payload" }
        assert(payload.contains("CIRCUIT_BREAK"))
        assert(payload.contains("\"executed_at\""))
    }

    @Test
    fun `실행 후 발행 실패는 예외 없이 false - 실행은 물리적 사실이라 롤백할 본체가 없다`() {
        val publisher = RecordingPublisher(accepted = false)

        val accepted = service(publisher = publisher).publishExecutedDecision(
            ApprovalDecided(incidentId, ApprovalStatus.APPROVED, "U0123ABC", Instant.now()),
            emptyList(),
            Instant.now(),
        )

        assertEquals(false, accepted)
    }

    @Test
    fun `종결 상태가 아닌 값의 decide 는 거부된다 - 입력 채널의 오호출 방어`() {
        assertFailsWith<IllegalArgumentException> {
            service().decide(incidentId, ApprovalStatus.PENDING, decidedBy = "U0123ABC")
        }
    }

    @Test
    fun `카드 좌표 기록은 저장소에 위임한다`() {
        val repository = RecordingRepository()

        service(repository).recordSlackMessage(incidentId, SlackMessageRef("C0123", "1722672000.000100"))

        val (recordedId, message) = repository.recordedMessages.single()
        assertEquals(incidentId, recordedId)
        assertEquals("C0123", message.channel)
    }
}
