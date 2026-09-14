package stillframe42.controlplane.incident.notify

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.web.client.RestClient
import stillframe42.controlplane.approval.model.ActionExecution
import stillframe42.controlplane.incident.model.IncidentReport

/**
 * 메시지 포맷 규약 검증 — HTTP 전송은 하지 않는다 (URL 미설정 경로 포함).
 * 포맷 스펙: P-등급·원인 가설·confidence·근거 3줄·제안 조치·상세 링크.
 */
class SlackIncidentReportNotifierTest {

    private fun notifier(webhookUrl: String = "") =
        SlackIncidentReportNotifier(webhookUrl = webhookUrl, baseUrl = "http://localhost:8081", restClientBuilder = RestClient.builder())

    private fun completedReport() = IncidentReport(
        incidentId = "inc-error-rate-surge-20260727031500-a1b2c3",
        scenario = "error-rate-surge",
        alertName = "TargetAppHighErrorRate",
        status = "completed",
        severity = "P2",
        rootCause = "배포 직후 신규 코드 결함으로 5xx 급증",
        confidence = 0.85,
        evidence = listOf("근거1", "근거2", "근거3", "근거4"),
        suggestedActions = listOf("ROLLBACK", "NOTIFY_ONLY"),
        completedAt = Instant.parse("2026-07-27T03:20:11Z"),
        raw = "{}",
    )

    @Test
    fun `완주 보고서 메시지는 등급·가설·confidence·근거·조치·링크를 담는다`() {
        val message = notifier().buildMessage(completedReport())

        assertTrue(message.contains("[P2] error-rate-surge 분석 보고"))
        assertTrue(message.contains("(completed)"))
        assertTrue(message.contains("inc-error-rate-surge-20260727031500-a1b2c3"))
        assertTrue(message.contains("배포 직후 신규 코드 결함으로 5xx 급증"))
        assertTrue(message.contains("confidence: 0.85"))
        assertTrue(message.contains("제안 조치: ROLLBACK, NOTIFY_ONLY"))
        assertTrue(message.contains("http://localhost:8081/api/incidents/inc-error-rate-surge-20260727031500-a1b2c3"))
    }

    @Test
    fun `승인·실행·회복 요약이 있으면 종결 보고에 싣는다 - 실행 결과 보고 스펙 (DAY 24)`() {
        val message = notifier().buildMessage(
            completedReport().copy(
                approvalStatus = "approved",
                approvalDecidedBy = "U0123ABC",
                executions = listOf(
                    ActionExecution("CIRCUIT_BREAK", true, "chaos/reset 호출 완료"),
                    ActionExecution("RESTART_APP", true, "운영자 직접 실행 대상", manual = true),
                ),
                executedAt = Instant.parse("2026-08-04T01:05:00Z"),
                recoveryStatus = "recovered",
                recoveryDetail = "Alert 해소 확인",
            ),
        )

        assertTrue(message.contains("승인: approved (by U0123ABC)"))
        assertTrue(message.contains("조치 실행: CIRCUIT_BREAK 성공, RESTART_APP 수동 안내"))
        assertTrue(message.contains("회복: recovered — Alert 해소 확인"))
    }

    @Test
    fun `승인 왕복 없던 보고서는 승인·회복 줄 자체가 없다`() {
        val message = notifier().buildMessage(completedReport())

        assertFalse(message.contains("승인:"))
        assertFalse(message.contains("회복:"))
    }

    @Test
    fun `근거는 3줄까지만 싣는다 - 전체는 상세 링크 몫`() {
        val message = notifier().buildMessage(completedReport())

        assertTrue(message.contains("근거3"))
        assertFalse(message.contains("근거4"))
    }

    @Test
    fun `분석 없는 partial 보고서도 강등 문구로 조립된다 - 실패 침묵 금지`() {
        val message = notifier().buildMessage(
            completedReport().copy(
                status = "partial",
                severity = null,
                rootCause = null,
                confidence = null,
                evidence = emptyList(),
                suggestedActions = emptyList(),
            ),
        )

        assertTrue(message.contains("[P?]"))
        assertTrue(message.contains("(partial)"))
        assertTrue(message.contains("분석 미완"))
        assertFalse(message.contains("근거:"))
        assertFalse(message.contains("제안 조치:"))
    }

    @Test
    fun `URL 미설정이면 notify 는 전송 시도 없이 조용히 반환한다`() {
        // 예외 없이 통과하면 성공 — blank 분기가 HTTP 클라이언트에 닿기 전에 반환한다
        notifier(webhookUrl = "").notify(completedReport())
        assertEquals(Unit, Unit)
    }
}
