package stillframe42.controlplane.evaluation.model

/** 실험 variant 1개의 집계 — 평균은 산술 평균(앵커 4단계 점수), lowQualityRate 는 발행 측 low_quality 판정 비율 */
data class ExperimentVariantSummary(
    val variant: String,
    val n: Int,
    val faithfulnessAvg: Double,
    val actionabilityAvg: Double,
    val severityAccuracyAvg: Double,
    val lowQualityRate: Double,
)
