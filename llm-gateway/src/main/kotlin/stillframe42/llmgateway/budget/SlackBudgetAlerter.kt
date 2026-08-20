package stillframe42.llmgateway.budget

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper

/**
 * 예산 임계 경고의 Slack Incoming Webhook 구현 (Phase 4) — URL 미설정이면 조용한 비활성,
 * 발송 실패는 로그만 (control-plane SlackNotifier 관례 승계 — 알림 실패가 요청 처리에 무해).
 * 타임아웃은 Boot 중앙 설정(spring.http.clients.*) — 주입 빌더가 반영한다 (8/19 검토: 사용처별 조립 중복 제거).
 */
@Component
class SlackBudgetAlerter(
    @Value("\${gateway.alert.slack-webhook-url}") private val webhookUrl: String,
    restClientBuilder: RestClient.Builder,
) : BudgetAlerter {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val restClient = restClientBuilder.build()

    private val jsonMapper = JsonMapper.builder().build()

    init {
        // 기동 시점 1회 진단 로그 — "왜 경고가 안 오지"를 로그로 확인 가능하게 (URL 값은 미출력)
        logger.info("예산 Slack 경고 {}", if (webhookUrl.isBlank()) "비활성 — SLACK_WEBHOOK_URL 미설정" else "활성")
    }

    override fun alert(message: String) {
        if (webhookUrl.isBlank()) {
            logger.warn("예산 경고 (Slack 비활성 — 로그 대체): {}", message)
            return
        }
        runCatching {
            restClient.post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonMapper.writeValueAsString(mapOf("text" to message)))
                .retrieve()
                .toBodilessEntity()
        }.onFailure {
            logger.warn("예산 경고 Slack 전송 실패: {}", it.message)
        }.onSuccess {
            logger.info("예산 경고 Slack 발송: {}", message)
        }
    }
}
