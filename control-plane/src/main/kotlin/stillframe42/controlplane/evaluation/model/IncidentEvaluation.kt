package stillframe42.controlplane.evaluation.model

import java.time.Instant
import java.time.OffsetDateTime
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * ops.evaluation.results 수신 페이로드의 소비 측 표현 — 발행 측은 evaluation-service `Evaluation.to_payload()`.
 * 차원 점수·유형·판정을 추출하고 원문 전체는 raw 로 보존한다 (IncidentReport 와 같은 원칙 — jsonb 저장).
 */
data class IncidentEvaluation(
    val incidentId: String,
    val promptVersion: String,
    val judgeModel: String,
    val analysisPromptVersion: String?,
    /** A/B 실험 축 (ADR-0019 결정 ③) — 실험 밖 평가는 둘 다 null. variant 만 null 이면 실험 요약에서 제외된다 */
    val experimentName: String?,
    val experimentVariant: String?,
    val faithfulness: Double,
    val actionability: Double,
    val severityAccuracy: Double,
    val failureMode: String,
    val lowQuality: Boolean,
    val evidenceAvailable: Boolean,
    val evaluatedAt: Instant,
    val raw: String,
) {

    /** 저장 시점의 검토 상태 — 저품질만 사람 검토 큐에 오른다 (docs/quality-evaluation.md §2 임계) */
    fun initialReviewStatus(): ReviewStatus = if (lowQuality) ReviewStatus.PENDING_REVIEW else ReviewStatus.NOT_REQUIRED

    companion object {
        private val mapper = JsonMapper.builder().build()
        private val dimensions = listOf("faithfulness", "actionability", "severity_accuracy")

        /** 파싱 불가·필수 필드 누락은 null — 호출 측이 경고 후 건너뛴다 (poison pill 이 오프셋 커밋을 막지 않게, IncidentReport 와 같은 규약) */
        fun parse(payload: String): IncidentEvaluation? {
            val root = runCatching { mapper.readTree(payload) }.getOrNull() ?: return null
            val incidentId = root.path("incident_id").stringOrNull() ?: return null
            val promptVersion = root.path("prompt_version").stringOrNull() ?: return null
            val judgeModel = root.path("judge_model").stringOrNull() ?: return null
            val scores = root.path("scores")
            val values = dimensions.map { scores.path(it).path("score").doubleOrNull() ?: return null }
            val failureMode = root.path("failure_mode").stringOrNull() ?: return null
            val evaluatedAt = root.path("evaluated_at").stringOrNull()
                ?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: return null
            return IncidentEvaluation(
                incidentId = incidentId,
                promptVersion = promptVersion,
                judgeModel = judgeModel,
                analysisPromptVersion = root.path("analysis_prompt_version").stringOrNull(),
                experimentName = root.path("experiment_name").stringOrNull(),
                experimentVariant = root.path("experiment_variant").stringOrNull(),
                faithfulness = values[0],
                actionability = values[1],
                severityAccuracy = values[2],
                failureMode = failureMode,
                lowQuality = root.path("low_quality").let { it.isBoolean && it.booleanValue() },
                evidenceAvailable = root.path("evidence_available").let { it.isBoolean && it.booleanValue() },
                evaluatedAt = evaluatedAt,
                raw = payload,
            )
        }

        private fun JsonNode.stringOrNull(): String? = if (isString) stringValue() else null

        private fun JsonNode.doubleOrNull(): Double? = if (isNumber) doubleValue() else null
    }
}
