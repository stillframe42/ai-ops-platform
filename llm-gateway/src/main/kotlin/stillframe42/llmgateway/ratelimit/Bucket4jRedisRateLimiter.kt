package stillframe42.llmgateway.ratelimit

import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce
import io.lettuce.core.RedisClient
import java.time.Duration
import java.util.function.Supplier
import kotlin.math.ceil
import org.slf4j.LoggerFactory

/**
 * 분산 토큰 버킷 (Bucket4j + Redis) — replica 2 전제로 버킷 상태를 Redis 에 둔다 (순서 검토 ⑤).
 * 연결은 최초 판정 시점에 지연 수립, 장애 = 통과 (fail-open — 한도 통제보다 가용성 우선,
 * 강제 차단 요건은 보안 주간 fail-closed 재검토와 결합).
 */
class Bucket4jRedisRateLimiter(
    private val redisClient: RedisClient,
    private val rateLimitProperties: RateLimitProperties,
) : RateLimiter {

    private val logger = LoggerFactory.getLogger(javaClass)

    // 지연 수립 — 기동이 Redis 가용성에 결합되지 않도록 (Redis 헬스 인디케이터 비활성 결정과 정합)
    private val proxyManager by lazy {
        Bucket4jLettuce.casBasedBuilder(redisClient)
            // 버킷 리필 완료 후에는 키를 유지할 이유가 없다 — 유휴 서비스 키 자연 소멸
            .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(2)))
            .build()
    }

    override fun tryConsume(service: String): RateLimitDecision = try {
        val rpm = (rateLimitProperties.serviceRpm[service] ?: rateLimitProperties.defaultRpm ?: return RateLimitDecision(true)).toLong()
        val configuration = BucketConfiguration.builder()
            .addLimit { it.capacity(rpm).refillGreedy(rpm, Duration.ofMinutes(1)) }
            .build()
        val bucket = proxyManager.builder().build("gw:rl:$service".toByteArray(), Supplier { configuration })
        val probe = bucket.tryConsumeAndReturnRemaining(1)
        if (probe.isConsumed) {
            RateLimitDecision(allowed = true)
        } else {
            RateLimitDecision(
                allowed = false,
                retryAfterSeconds = ceil(probe.nanosToWaitForRefill / 1_000_000_000.0).toLong().coerceAtLeast(1),
            )
        }
    } catch (e: Exception) {
        logger.warn("rate limit 판정 실패 — 통제 없이 통과: {}", e.message)
        RateLimitDecision(allowed = true)
    }
}
