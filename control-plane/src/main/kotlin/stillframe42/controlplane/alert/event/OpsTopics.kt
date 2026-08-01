package stillframe42.controlplane.alert.event

/**
 * Kafka 토픽 명세의 코드 측 상수 (DAY 17) — 파티션·보존 설정은 infra 의 kafka-init 가 소유한다.
 * analysis.results 는 agent-service 발행(DAY 19). actions.pending/decisions 는 승인 왕복
 * (DAY 22, ADR-0005): pending 은 agent-service 발행 → 여기서 소비, decisions 는 그 반대.
 */
object OpsTopics {
    const val ALERTS_RAW = "ops.alerts.raw"
    const val INCIDENTS = "ops.incidents"
    const val ANALYSIS_RESULTS = "ops.analysis.results"
    const val ACTIONS_PENDING = "ops.actions.pending"
    const val ACTIONS_DECISIONS = "ops.actions.decisions"
}
