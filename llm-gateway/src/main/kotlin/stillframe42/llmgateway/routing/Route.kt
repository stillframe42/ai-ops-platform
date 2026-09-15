package stillframe42.llmgateway.routing

/** 라우팅 해석 결과 — 캐시 키·예산 판정·중계·비용 기록이 공유하는 단일 좌표 */
data class Route(
    val taskType: String?,
    val provider: Provider,
    val model: String,
    val maxTokens: Int?,
    /** 실험 variant 오버라이드가 적용됐을 때만 `<name>:<variant>` — 캐시 키·비용 라벨의 실험 축 (미적용 = null) */
    val variant: String? = null,
)
