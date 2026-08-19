package stillframe42.llmgateway.cost

/** 비용 원장 저장소 포트 — 구현이 영속 방식(PostgreSQL)과 장애 무해(guard)를 소유한다 */
interface CostLedger {
    fun append(entry: CostEntry)
}
