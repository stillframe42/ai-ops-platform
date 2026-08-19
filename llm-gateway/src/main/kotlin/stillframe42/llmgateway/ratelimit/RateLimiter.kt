package stillframe42.llmgateway.ratelimit

data class RateLimitDecision(val allowed: Boolean, val retryAfterSeconds: Long = 0)

/**
 * 서비스별 분당 한도 판정 포트 (weekly-plan Phase 4) — 구현이 버킷 저장소(Bucket4j + Redis)와
 * 저장소 장애 시 통과(fail-open)를 소유한다.
 */
interface RateLimiter {
    fun tryConsume(service: String): RateLimitDecision
}
