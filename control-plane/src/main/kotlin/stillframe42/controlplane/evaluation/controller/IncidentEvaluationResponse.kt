package stillframe42.controlplane.evaluation.controller

import com.fasterxml.jackson.annotation.JsonProperty
import stillframe42.controlplane.evaluation.model.IncidentEvaluationDetail
import tools.jackson.databind.JsonNode

/** 평가 조회 응답 — 와이어(snake_case)를 타입으로 고정 (IncidentSummaryResponse 와 같은 취지). 차원별 점수·reason 은 scores 객체로 */
data class IncidentEvaluationResponse(
    @JsonProperty("id") val id: Long,
    @JsonProperty("incident_id") val incidentId: String,
    @JsonProperty("prompt_version") val promptVersion: String,
    @JsonProperty("judge_model") val judgeModel: String,
    @JsonProperty("analysis_prompt_version") val analysisPromptVersion: String?,
    @JsonProperty("scores") val scores: JsonNode,
    @JsonProperty("failure_mode") val failureMode: String,
    @JsonProperty("low_quality") val lowQuality: Boolean,
    @JsonProperty("evidence_available") val evidenceAvailable: Boolean,
    @JsonProperty("review_status") val reviewStatus: String,
    @JsonProperty("evaluated_at") val evaluatedAt: String,
    @JsonProperty("created_at") val createdAt: String,
    @JsonProperty("updated_at") val updatedAt: String,
) {
    companion object {
        fun from(detail: IncidentEvaluationDetail, scores: JsonNode) = IncidentEvaluationResponse(
            id = detail.summary.id,
            incidentId = detail.summary.incidentId,
            promptVersion = detail.summary.promptVersion,
            judgeModel = detail.summary.judgeModel,
            analysisPromptVersion = detail.summary.analysisPromptVersion,
            scores = scores,
            failureMode = detail.summary.failureMode,
            lowQuality = detail.summary.lowQuality,
            evidenceAvailable = detail.summary.evidenceAvailable,
            reviewStatus = detail.summary.reviewStatus.wire,
            evaluatedAt = detail.summary.evaluatedAt.toString(),
            createdAt = detail.summary.createdAt.toString(),
            updatedAt = detail.summary.updatedAt.toString(),
        )
    }
}
