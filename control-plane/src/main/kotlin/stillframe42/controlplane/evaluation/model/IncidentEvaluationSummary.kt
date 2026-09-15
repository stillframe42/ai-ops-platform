package stillframe42.controlplane.evaluation.model

import java.time.Instant

/** 조회용 요약 — 원문(jsonb)은 제외, 차원별 reason 은 detail 이 원문에서 꺼낸다. 사람 검토 필드는 검토 전 null */
data class IncidentEvaluationSummary(
    val id: Long,
    val incidentId: String,
    val promptVersion: String,
    val judgeModel: String,
    val analysisPromptVersion: String?,
    val experimentName: String?,
    val experimentVariant: String?,
    val faithfulness: Double,
    val actionability: Double,
    val severityAccuracy: Double,
    val failureMode: String,
    val lowQuality: Boolean,
    val evidenceAvailable: Boolean,
    val reviewStatus: ReviewStatus,
    val evaluatedAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
    val humanScores: Map<String, Double>? = null,
    val humanFailureMode: String? = null,
    val reviewNote: String? = null,
    val reviewedBy: String? = null,
    val reviewedAt: Instant? = null,
)
