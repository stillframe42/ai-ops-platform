package stillframe42.controlplane.evaluation.model

/** 리뷰 API 의 결과 분기 — 컨트롤러가 상태 코드로 옮긴다 (ApprovalDecisionOutcome 과 같은 모양) */
sealed interface ReviewOutcome {
    data object NotFound : ReviewOutcome
    data class AlreadyPromoted(val detail: IncidentEvaluationDetail) : ReviewOutcome
    data class Reviewed(val detail: IncidentEvaluationDetail) : ReviewOutcome
}
