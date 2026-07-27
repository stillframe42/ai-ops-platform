package stillframe42.controlplane.incident.notify

import java.net.http.HttpClient
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import stillframe42.controlplane.incident.model.IncidentReport
import tools.jackson.databind.json.JsonMapper

/**
 * Slack Incoming Webhook 알림 (DAY 19) — URL 미설정이면 조용한 비활성 (Langfuse 키-게이트 관례).
 * 메시지 포맷은 weekly-plan 스펙: P-등급·원인 가설·confidence·근거 3줄·제안 조치·상세 링크 —
 * 4주차 human-in-the-loop 승인 요청 포맷의 초안이기도 하다 (ADR-0006 연결 메모).
 */
@Component
class SlackNotifier(
    @Value("\${ops.slack.webhook-url}") private val webhookUrl: String,
    @Value("\${ops.report.base-url}") private val baseUrl: String,
) : Notifier {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val restClient = RestClient.builder()
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
            ).apply { setReadTimeout(Duration.ofSeconds(5)) },
        )
        .build()

    private val mapper = JsonMapper.builder().build()

    init {
        // 기동 시점 1회 진단 로그 — "왜 Slack 이 안 오지" 를 로그로 확인 가능하게 (URL 값은 미출력)
        logger.info("Slack 알림 {}", if (webhookUrl.isBlank()) "비활성 — SLACK_WEBHOOK_URL 미설정" else "활성")
    }

    override fun notify(report: IncidentReport) {
        if (webhookUrl.isBlank()) {
            logger.info("Slack 알림 비활성 — {} 발송 생략", report.incidentId)
            return
        }
        // 전송 실패는 로그만 — 알림 실패가 저장·오프셋 커밋을 되돌리면 안 된다 (Notifier 계약)
        runCatching {
            restClient.post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(mapOf("text" to buildMessage(report))))
                .retrieve()
                .toBodilessEntity()
        }.onFailure {
            logger.warn("Slack 전송 실패 — {}: {}", report.incidentId, it.message)
        }.onSuccess {
            logger.info("Slack 알림 발송 — {}", report.incidentId)
        }
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
        lines += "• 상세: $baseUrl/api/incidents/${report.incidentId}"
        return lines.joinToString("\n")
    }

    companion object {
        /** 근거는 3줄까지 — 알림은 요약, 전체는 상세 링크가 담당 (weekly-plan 포맷 스펙). */
        private const val EVIDENCE_LINES = 3
    }
}
