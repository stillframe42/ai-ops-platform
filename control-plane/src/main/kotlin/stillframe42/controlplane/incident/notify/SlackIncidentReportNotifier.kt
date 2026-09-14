package stillframe42.controlplane.incident.notify

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import stillframe42.controlplane.incident.model.IncidentReport


import stillframe42.controlplane.slack.SlackWebhookClient
/**
 * 보고서 종결 알림 (DAY 19) — 전송·마스킹·비활성 분기는 SlackWebhookClient, 여기는 메시지 포맷만.
 * 메시지 포맷: P-등급·원인 가설·confidence·근거 3줄·제안 조치·상세 링크 —
 * human-in-the-loop 승인 요청 포맷의 원형이기도 하다 (ADR-0006).
 */
@Component
class SlackIncidentReportNotifier(
    private val slackWebhookClient: SlackWebhookClient,
    @param:Value("\${ops.report.base-url}") private val baseUrl: String,
) : IncidentReportNotifier {

    override fun notify(report: IncidentReport) {
        // 상세 링크(baseUrl)는 의도된 내부 링크라 마스킹 예외
        slackWebhookClient.post(buildMessage(report), report.incidentId, SOURCE, setOf(baseUrl))
    }

    /** mrkdwn 텍스트 조립 — 분석 실패(partial, analysis 없음)도 강등 문구로 발송한다 (침묵 금지). */
    fun buildMessage(report: IncidentReport): String {
        val lines = mutableListOf<String>()
        lines += ":rotating_light: *[${report.severity ?: "P?"}] ${report.scenario} 분석 보고* (${report.status})"
        lines += "• 인시던트: `${report.incidentId}`" +
            (report.alertName?.let { " ($it)" } ?: "")
        lines += "• 원인 가설: ${report.rootCause ?: "_분석 미완 — 부분 보고서_"}"
        lines += "• confidence: ${report.confidence?.let { "%.2f".format(it) } ?: "-"}"
        if (report.evidence.isNotEmpty()) {
            lines += "• 근거:"
            report.evidence.take(EVIDENCE_LINES).forEach { lines += "    - $it" }
        }
        if (report.suggestedActions.isNotEmpty()) {
            lines += "• 제안 조치: ${report.suggestedActions.joinToString(", ")}"
        }
        // 승인·실행·회복 요약 (DAY 24, scenarios.md 실행 결과 보고 스펙: 수행 내용/수행 시각/회복 여부)
        report.approvalStatus?.let { status ->
            lines += "• 승인: $status" +
                (report.approvalDecidedBy?.takeIf { it.isNotBlank() }?.let { " (by $it)" } ?: "")
        }
        if (report.executions.isNotEmpty()) {
            lines += "• 조치 실행: " +
                report.executions.joinToString(", ") {
                    "${it.action} ${if (it.manual) "수동 안내" else if (it.ok) "성공" else "실패"}"
                } +
                (report.executedAt?.let { " ($it)" } ?: "")
        }
        report.recoveryStatus?.let { status ->
            lines += "• 회복: $status" +
                (report.recoveryDetail?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
        }
        lines += "• 상세: $baseUrl/api/incidents/${report.incidentId}"
        return lines.joinToString("\n")
    }

    companion object {
        /** 근거는 3줄까지 — 알림은 요약, 전체는 상세 링크가 담당 (weekly-plan 포맷 스펙). */
        private const val EVIDENCE_LINES = 3
        private const val SOURCE = "slack-notify"
    }
}
