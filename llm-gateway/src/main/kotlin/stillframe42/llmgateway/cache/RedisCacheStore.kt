package stillframe42.llmgateway.cache

import java.time.Duration
import org.springframework.data.redis.core.StringRedisTemplate

/** 정확 일치 캐시의 Redis 구현 — 예외는 호출자(CachingChatService)가 무캐시 통과로 강등한다 */
class RedisCacheStore(
    private val stringRedisTemplate: StringRedisTemplate,
) : ExactMatchCacheStore {

    override fun get(key: String): String? = stringRedisTemplate.opsForValue().get(key)

    override fun put(key: String, value: String, ttl: Duration) {
        stringRedisTemplate.opsForValue().set(key, value, ttl)
    }
}
