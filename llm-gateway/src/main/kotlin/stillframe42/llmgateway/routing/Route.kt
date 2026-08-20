package stillframe42.llmgateway.routing

/** 라우팅 해석 결과 — 캐시 키·예산 판정·중계·비용 기록이 공유하는 단일 좌표 */
data class Route(
    val taskType: String?,
    val provider: Provider,
    val model: String,
    val maxTokens: Int?,
)
