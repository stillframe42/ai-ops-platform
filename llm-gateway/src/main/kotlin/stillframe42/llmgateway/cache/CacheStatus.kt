package stillframe42.llmgateway.cache

/** 캐시 판정 결과 — 응답 헤더(X-Gateway-Cache)·메트릭 라벨의 원천 */
enum class CacheStatus { EXACT_HIT, SEMANTIC_HIT, MISS, BYPASS }
