package stillframe42.controlplane.quality.notify

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import stillframe42.controlplane.quality.model.QualitySloAlert
import stillframe42.controlplane.slack.SlackWebhookClient

/**
 * 품질 SLO 알림 → Slack. 발화·해소 모두 보낸다 — SLO 는 추세라 "언제 회복했나" 가 발화만큼 중요하다.
 * 링크는 리뷰 큐 — 이 알림에 대응하는 사람의 첫 동작이 최근 저품질 케이스를 읽는 것이다.
 */
@Component
class SlackQualitySloNotifier(
    private val slackWebhookClient: SlackWebhookClient,
    @param:Value("\${ops.report.base-url}") private val baseUrl: String,
) : QualitySloNotifier {

    override fun notify(alert: QualitySloAlert) {
        slackWebhookClient.post(buildMessage(alert), alert.alertName, SOURCE, setOf(baseUrl))
    }

    fun buildMessage(alert: QualitySloAlert): String {
        val firing = alert.status == "firing"
        val lines = mutableListOf<String>()
        lines += (if (firing) ":chart_with_downwards_trend: *[품질 SLO] " else ":white_check_mark: *[품질 SLO 해소] ") +
            "${alert.alertName}*" + listOfNotNull(alert.severity, alert.cluster, experimentOf(alert)).takeIf { it.isNotEmpty() }?.joinToString(" · ", " (", ")").orEmpty()
        alert.summary?.let { lines += "• $it" }
        alert.description?.let { lines += "• $it" }
        alert.startsAt?.let { lines += "• 시작: $it" }
        if (firing) {
            lines += "• 리뷰 큐: $baseUrl/api/evaluations/review-queue?status=pending_review"
        }
        return lines.joinToString("\n")
    }

    // variant 룰은 "어느 처리군이 나빠졌나" 가 메시지의 핵심이라 머리에 둔다 — 승자 판정·중단은 사람이 리포트로 한다
    private fun experimentOf(alert: QualitySloAlert): String? =
        alert.experimentName?.let { name -> "실험 $name" + (alert.experimentVariant?.let { " · $it" } ?: "") }

    companion object {
        private const val SOURCE = "slack-quality-alert"
    }
}
