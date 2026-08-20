package stillframe42.llmgateway.cache

import stillframe42.llmgateway.api.ChatCompletionResponse

/** 캐싱 계층의 반환 모델 — 응답 본문·캐시 판정·예산 다운그레이드·폴백 여부를 함께 나른다 (컨트롤러가 헤더로 노출) */
data class CachedChatResult(
    val response: ChatCompletionResponse,
    val cacheStatus: CacheStatus,
    val downgraded: Boolean = false,
    /** 폴백 발생 시 대상 (교차 프로바이더명 또는 local), 미발생 = null */
    val fallbackTarget: String? = null,
)
