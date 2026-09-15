package stillframe42.controlplane.evaluation.controller

import com.fasterxml.jackson.annotation.JsonProperty
import stillframe42.controlplane.evaluation.model.ExperimentSummary
import stillframe42.controlplane.evaluation.model.ExperimentVariantSummary

/** 실험 요약 응답 — 와이어(snake_case)를 타입으로 고정 (IncidentEvaluationResponse 와 같은 취지) */
data class ExperimentSummaryResponse(
    @JsonProperty("experiment") val experiment: String,
    @JsonProperty("variants") val variants: List<Variant>,
) {
    data class Variant(
        @JsonProperty("variant") val variant: String,
        @JsonProperty("n") val n: Int,
        @JsonProperty("faithfulness_avg") val faithfulnessAvg: Double,
        @JsonProperty("actionability_avg") val actionabilityAvg: Double,
        @JsonProperty("severity_accuracy_avg") val severityAccuracyAvg: Double,
        @JsonProperty("low_quality_rate") val lowQualityRate: Double,
    )

    companion object {
        fun from(summary: ExperimentSummary) = ExperimentSummaryResponse(
            experiment = summary.experiment,
            variants = summary.variants.map { it.toVariant() },
        )

        private fun ExperimentVariantSummary.toVariant() = Variant(
            variant = variant,
            n = n,
            faithfulnessAvg = faithfulnessAvg,
            actionabilityAvg = actionabilityAvg,
            severityAccuracyAvg = severityAccuracyAvg,
            lowQualityRate = lowQualityRate,
        )
    }
}
