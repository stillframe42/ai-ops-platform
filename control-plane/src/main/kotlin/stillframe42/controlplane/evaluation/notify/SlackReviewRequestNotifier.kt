package stillframe42.controlplane.evaluation.notify

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import stillframe42.controlplane.evaluation.model.IncidentEvaluation
import stillframe42.controlplane.slack.SlackWebhookClient
import tools.jackson.databind.json.JsonMapper

/**
 * 저품질 평가 → Slack 검토 요청 (docs/quality-evaluation.md §1 "Judge 가 낮게 매긴 케이스는 사람이 다시 보고 골든셋으로 승격").
 * 메시지 = 인시던트·세 차원 점수·실패 유형·0.7 미만 차원의 Judge 사유·검토 API 경로 — 사람이 보고서를 다시 읽고
 * 사람 점수를 매기는 데 필요한 것만. Judge 점수 자체를 정답처럼 보이게 하지 않으려고 "Judge 사유" 라고 적는다.
 */
@Component
class SlackReviewRequestNotifier(
    private val slackWebhookClient: SlackWebhookClient,
    @param:Value("\${ops.report.base-url}") private val baseUrl: String,
) : ReviewRequestNotifier {

    private val mapper = JsonMapper.builder().build()

    override fun requestReview(id: Long, evaluation: IncidentEvaluation) {
        slackWebhookClient.post(buildMessage(id, evaluation), evaluation.incidentId, SOURCE, setOf(baseUrl))
    }

    fun buildMessage(id: Long, evaluation: IncidentEvaluation): String {
        val scores = mapper.readTree(evaluation.raw).path("scores")
        val lines = mutableListOf<String>()
        lines += ":mag: *[품질] 저품질 보고서 — 사람 검토 요청* (실패 유형 ${evaluation.failureMode})"
        lines += "• 인시던트: `${evaluation.incidentId}`"
        lines += "• Judge 점수: F ${evaluation.faithfulness} · A ${evaluation.actionability} · S ${evaluation.severityAccuracy}" +
            " (${evaluation.judgeModel}, Judge 프롬프트 ${evaluation.promptVersion}" +
            (evaluation.analysisPromptVersion?.let { ", 분석 프롬프트 $it" } ?: "") + ")"
        LOW_DIMENSIONS.filter { (dimension, _) -> scores.path(dimension).path("score").asDouble(1.0) < LOW_QUALITY_THRESHOLD }
            .forEach { (dimension, label) ->
                val reason = scores.path(dimension).path("reason").asString("").ifBlank { "-" }
                lines += "• $label Judge 사유: $reason"
            }
        lines += "• 보고서: $baseUrl/api/incidents/${evaluation.incidentId}"
        lines += "• 검토: `POST $baseUrl/api/evaluations/$id/review` (status=reviewed|promoted|dismissed, human_scores, failure_mode, note)"
        return lines.joinToString("\n")
    }

    companion object {
        private const val SOURCE = "slack-evaluation-review"
        private const val LOW_QUALITY_THRESHOLD = 0.7
        private val LOW_DIMENSIONS = listOf(
            "faithfulness" to "Faithfulness",
            "actionability" to "Actionability",
            "severity_accuracy" to "Severity",
        )
    }
}
