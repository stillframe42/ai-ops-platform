package stillframe42.llmgateway.budget

/** 예산 임계 경고 발송 포트 — 발송 실패는 요청 처리에 무해해야 한다 (Notifier 계약 승계) */
fun interface BudgetAlerter {
    fun alert(message: String)
}
