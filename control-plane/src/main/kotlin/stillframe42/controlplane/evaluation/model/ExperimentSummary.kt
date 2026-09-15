package stillframe42.controlplane.evaluation.model

/** A/B 실험 요약 (ADR-0019 결정 ③) — variants 는 이름순. 평가가 없는 실험은 빈 목록 */
data class ExperimentSummary(val experiment: String, val variants: List<ExperimentVariantSummary>)
