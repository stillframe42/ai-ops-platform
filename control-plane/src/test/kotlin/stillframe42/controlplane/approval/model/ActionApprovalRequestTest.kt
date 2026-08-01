package stillframe42.controlplane.approval.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * ops.actions.pending 페이로드 파싱 계약 — 발행 측은 agent-service build_approval_request.
 * 파싱 불가·필수 누락은 null (poison pill 이 오프셋 커밋을 막으면 안 됨 — IncidentReport 와 같은 규약).
 */
class ActionApprovalRequestTest {

    private fun payload(
        incidentId: String = "inc-memory-pressure-20260801100000-ab12cd",
        actions: String = """["RESTART_APP"]""",
        requestedAt: String? = "2026-08-01T10:00:00+00:00",
    ) = buildString {
        append("""{"incident_id": "$incidentId", "scenario": "memory-pressure",""")
        append(""""alert_name": "TargetAppHeapUsageHigh", "severity": "P1", "confidence": 0.9,""")
        append(""""root_cause_hypothesis": "heap 누수 의심", "actions": $actions,""")
        append(""""rationale": "재시작 필요", "expected_effect": "heap 정상화", "risk": "요청 유실",""")
        if (requestedAt != null) append(""""requested_at": "$requestedAt",""")
        append(""""_end": true}""")
    }

    @Test
    fun `정상 페이로드는 요약 필드를 추출하고 원문을 보존한다`() {
        val raw = payload()

        val request = assertNotNull(ActionApprovalRequest.parse(raw))

        assertEquals("inc-memory-pressure-20260801100000-ab12cd", request.incidentId)
        assertEquals("RESTART_APP", request.actionType)
        assertEquals("P1", request.riskLevel)
        assertEquals(0.9, request.confidence)
        // Python isoformat(+00:00) → Instant 정규화 (IncidentReport.completedAt 과 같은 경로)
        assertEquals(Instant.parse("2026-08-01T10:00:00Z"), request.requestedAt)
        assertEquals(raw, request.raw)
    }

    @Test
    fun `대표 조치는 NOTIFY_ONLY 를 제외한 첫 실행 조치다`() {
        val request = ActionApprovalRequest.parse(payload(actions = """["NOTIFY_ONLY", "RESTART_APP"]"""))

        assertEquals("RESTART_APP", assertNotNull(request).actionType)
    }

    @Test
    fun `requested_at 이 없으면 수신 시각으로 폴백한다 - 컬럼 NOT NULL`() {
        val before = Instant.now()
        val request = assertNotNull(ActionApprovalRequest.parse(payload(requestedAt = null)))

        // 폴백 시각은 파싱 시점 — 테스트 실행 구간 안이면 충분
        assert(!request.requestedAt.isBefore(before))
    }

    @Test
    fun `incident_id 누락은 null`() {
        assertNull(ActionApprovalRequest.parse("""{"actions": ["RESTART_APP"]}"""))
    }

    @Test
    fun `actions 가 비어 있으면 null - 승인할 실행 조치가 없다`() {
        assertNull(ActionApprovalRequest.parse(payload(actions = "[]")))
    }

    @Test
    fun `파싱 불가 본문은 null`() {
        assertNull(ActionApprovalRequest.parse("not-json{{{"))
    }
}
