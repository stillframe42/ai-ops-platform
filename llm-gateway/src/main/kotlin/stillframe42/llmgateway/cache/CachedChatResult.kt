package stillframe42.llmgateway.cache

import stillframe42.llmgateway.api.ChatCompletionResponse

/** 캐싱 계층의 반환 모델 — 응답 본문과 캐시 판정을 함께 나른다 (판정은 컨트롤러가 헤더로 노출) */
data class CachedChatResult(
    val response: ChatCompletionResponse,
    val cacheStatus: CacheStatus,
)
