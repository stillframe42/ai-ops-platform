package stillframe42.controlplane.evaluation.model

/** 요약 + 발행 페이로드 원문 (차원별 reason 포함) */
data class IncidentEvaluationDetail(val summary: IncidentEvaluationSummary, val evaluation: String)
