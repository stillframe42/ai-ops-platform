package stillframe42.llmgateway.budget

/**
 * 예산 누계 카운터 포트 — 외부 저장 필수 (replica 2 전제, in-memory 금지).
 * 구현은 저장소 장애 시 0·false 를 돌려 통제 없이 통과시킨다 (캐시 fail-open 관례).
 */
interface BudgetCounter {
    /** 가산 후 누계 반환 */
    fun add(scope: String, amount: Double): Double

    fun current(scope: String): Double

    /** 최초 1회만 true — 임계 경고의 중복 발송 방지 */
    fun markOnce(flag: String): Boolean
}
