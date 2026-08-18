package stillframe42.llmgateway.cache

import java.time.Duration

/**
 * 정확 일치 캐시의 저장소 계약 — 구현은 Redis (표준 이미지, Phase 0 결정 ⑦).
 * 장애 시 예외를 그대로 던진다 — 무캐시 통과 판단은 호출자(CachingChatService) 소관.
 */
interface ExactMatchCacheStore {
    fun get(key: String): String?
    fun put(key: String, value: String, ttl: Duration)
}
