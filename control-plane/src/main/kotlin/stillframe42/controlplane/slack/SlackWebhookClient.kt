package stillframe42.controlplane.slack

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import stillframe42.controlplane.audit.AuditLog
import stillframe42.controlplane.security.SensitiveOutputMasker
import tools.jackson.databind.json.JsonMapper

/**
 * Slack Incoming Webhook 전송 — 보고서 알림·저품질 검토 요청·품질 SLO 알림이 같은 URL·같은 마스킹·같은 실패 정책을 공유한다.
 * URL 미설정이면 조용한 비활성 (Langfuse 키-게이트 관례). 전송 실패는 로그만 — 알림 실패가 저장·오프셋 커밋을 되돌리면 안 된다.
 * 타임아웃은 Boot 중앙 설정(spring.http.clients.*) — 주입 빌더가 반영한다.
 */
@Component
class SlackWebhookClient(
    @param:Value("\${ops.slack.webhook-url}") private val webhookUrl: String,
    restClientBuilder: RestClient.Builder,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val restClient = restClientBuilder.build()

    private val mapper = JsonMapper.builder().build()

    val active: Boolean = webhookUrl.isNotBlank()

    init {
        // 기동 시점 1회 진단 로그 — "왜 Slack 이 안 오지" 를 로그로 확인 가능하게 (URL 값은 미출력)
        logger.info("Slack 알림 {}", if (active) "활성" else "비활성 — SLACK_WEBHOOK_URL 미설정")
    }

    /**
     * mrkdwn 텍스트 1건 발송. 발송 직전 마스킹 — 외부 채널이라 시크릿·내부 URL 을 싣지 않는다. allowedUrlPrefixes 는 의도된 내부 링크.
     * @param subject 로그·감사용 식별자 (인시던트 id 등), @param source 감사 로그의 발신 경로 이름 (Slack 채널이 아니다 — 목적지는 웹훅 URL 에 묶인 채널 하나)
     */
    fun post(text: String, subject: String, source: String, allowedUrlPrefixes: Set<String>): Boolean {
        if (!active) {
            logger.info("Slack 알림 비활성 — {} 발송 생략 ({})", subject, source)
            return false
        }
        val message = SensitiveOutputMasker.mask(text, allowedUrlPrefixes)
        if (message.hits.isNotEmpty()) {
            AuditLog.record(
                type = "output_masked",
                fields = mapOf("incident_id" to subject, "channel" to source, "hits" to message.hits.joinToString(",")),
                message = "Slack 발송 전 마스킹 — ${message.hits} ($subject)",
            )
        }
        return runCatching {
            restClient.post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(mapOf("text" to message.text)))
                .retrieve()
                .toBodilessEntity()
        }.onFailure {
            logger.warn("Slack 전송 실패 — {} ({}): {}", subject, source, it.message)
        }.onSuccess {
            logger.info("Slack 알림 발송 — {} ({})", subject, source)
        }.isSuccess
    }
}
