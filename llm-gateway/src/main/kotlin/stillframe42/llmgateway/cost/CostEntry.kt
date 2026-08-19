package stillframe42.llmgateway.cost

import stillframe42.llmgateway.cache.CacheStatus

/** 요청 1건의 비용 원장 기록 — 집계 차원(서비스/태스크/모델/일별)은 저장소 질의가 담당 */
data class CostEntry(
    val service: String,
    val task: String?,
    val provider: String,
    val model: String,
    val cacheStatus: CacheStatus,
    val promptTokens: Int,
    val completionTokens: Int,
    val costUsd: Double,
    val savedUsd: Double,
)
