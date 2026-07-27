package stillframe42.controlplane.incident.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 수신 파싱 규약 — 발행 측(agent-service get_result)의 페이로드 형태를 여기 픽스처가 고정한다.
 * 발행 측 필드가 바뀌면 이 테스트가 계약 위반을 드러낸다 (IncidentEventTest 의 소비 측 대응).
 */
class IncidentReportTest {

    private fun completedPayload() = """
        {
          "incident_id": "inc-error-rate-surge-20260727031500-a1b2c3",
          "scenario": "error-rate-surge",
          "alert_name": "TargetAppHighErrorRate",
          "status": "completed",
          "monitoring": {"situation_summary": "5xx 비율 12%", "evidences": ["rate 쿼리 결과"]},
          "analysis": {
            "root_cause_hypothesis": "배포 직후 신규 코드 결함으로 5xx 급증",
            "evidence": ["근거1", "근거2", "근거3", "근거4"],
            "confidence": 0.85,
            "suggested_actions": ["ROLLBACK", "NOTIFY_ONLY"],
            "severity": "P2"
          },
          "action": {"actions": ["ROLLBACK"], "rationale": "직전 배포와 시점 일치", "expected_effect": "", "risk": ""},
          "errors": [],
          "pending_errors": [],
          "supervisor_visits": 4,
          "completed_at": "2026-07-27T03:20:11.123456+00:00"
        }
    """.trimIndent()

    @Test
    fun `완주 보고서에서 요약 필드를 추출하고 원문을 보존한다`() {
        val payload = completedPayload()

        val report = IncidentReport.parse(payload)!!

        assertEquals("inc-error-rate-surge-20260727031500-a1b2c3", report.incidentId)
        assertEquals("error-rate-surge", report.scenario)
        assertEquals("TargetAppHighErrorRate", report.alertName)
        assertEquals("completed", report.status)
        assertEquals("P2", report.severity)
        assertEquals("배포 직후 신규 코드 결함으로 5xx 급증", report.rootCause)
        assertEquals(0.85, report.confidence)
        assertEquals(listOf("근거1", "근거2", "근거3", "근거4"), report.evidence)
        assertEquals(listOf("ROLLBACK", "NOTIFY_ONLY"), report.suggestedActions)
        // Python isoformat(+00:00 오프셋)이 UTC 시점(Instant)으로 정규화된다
        assertEquals(Instant.parse("2026-07-27T03:20:11.123456Z"), report.completedAt)
        assertEquals(payload, report.raw)
    }

    @Test
    fun `analysis 없는 partial 보고서는 분석 요약이 null 이고 파싱은 성공한다`() {
        val report = IncidentReport.parse(
            """
            {
              "incident_id": "inc-memory-pressure-20260727031500-b2c3d4",
              "scenario": "memory-pressure",
              "alert_name": "TargetAppHeapUsageHigh",
              "status": "partial",
              "monitoring": null,
              "analysis": null,
              "action": null,
              "errors": [{"node": "analysis", "error_type": "NodeTimeoutError", "message": "timeout", "occurred_at": "2026-07-27T03:19:00+00:00"}],
              "completed_at": "2026-07-27T03:20:11+00:00"
            }
            """.trimIndent(),
        )!!

        assertEquals("partial", report.status)
        assertNull(report.severity)
        assertNull(report.rootCause)
        assertNull(report.confidence)
        assertEquals(emptyList(), report.evidence)
        assertEquals(emptyList(), report.suggestedActions)
    }

    @Test
    fun `incident_id 없는 페이로드는 null - 필수 필드 계약`() {
        assertNull(IncidentReport.parse("""{"scenario": "unknown", "status": "partial"}"""))
    }

    @Test
    fun `JSON 이 아닌 본문은 null - poison pill 규약`() {
        assertNull(IncidentReport.parse("not-json{{{"))
    }
}
