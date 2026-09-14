package stillframe42.controlplane.evaluation.controller

import com.fasterxml.jackson.annotation.JsonProperty
import stillframe42.controlplane.evaluation.model.EvaluationReview
import stillframe42.controlplane.evaluation.model.ReviewStatus

/** 리뷰 요청 본문 — 라벨 규약 검증은 EvaluationReview 가 한다 (여기는 와이어 이름만). 검토 주체 미지정 시 "api" */
data class ReviewRequest(
    @field:JsonProperty("status") val status: String,
    @field:JsonProperty("human_scores") val humanScores: Map<String, Double>? = null,
    @field:JsonProperty("failure_mode") val failureMode: String? = null,
    @field:JsonProperty("note") val note: String? = null,
    @field:JsonProperty("reviewed_by") val reviewedBy: String? = null,
) {
    fun toReview(): EvaluationReview = EvaluationReview(
        status = ReviewStatus.fromWireOrNull(status) ?: throw IllegalArgumentException("모르는 status: $status"),
        humanScores = humanScores,
        failureMode = failureMode,
        note = note,
        reviewedBy = reviewedBy?.takeIf { it.isNotBlank() } ?: DEFAULT_REVIEWER,
    )

    companion object {
        private const val DEFAULT_REVIEWER = "api"
    }
}
