package stillframe42.llmgateway.budget

import stillframe42.llmgateway.routing.Route

/** 예산 판정 결과 — 한도 초과면 저비용 다운그레이드 라우트로 교체된다 (차단 없음) */
data class BudgetDecision(val route: Route, val downgraded: Boolean)
