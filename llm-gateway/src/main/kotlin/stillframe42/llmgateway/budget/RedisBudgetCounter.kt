package stillframe42.llmgateway.budget

import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * 예산 누계의 Redis 구현 — replica 2 전제 외부 저장.
 * 저장소 장애 = 0·false 반환으로 통제 없이 통과 (캐시 fail-open 관례) — 영구 기록은 원장(PostgreSQL) 소관이라
 * 카운터 유실은 당일 통제 정확도만 낮춘다.
 */
class RedisBudgetCounter(
    private val stringRedisTemplate: StringRedisTemplate,
) : BudgetCounter {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun add(scope: String, amount: Double): Double = guarded("add", 0.0) {
        val key = key(scope)
        val total = checkNotNull(stringRedisTemplate.opsForValue().increment(key, amount))
        stringRedisTemplate.expire(key, RETENTION)
        total
    }

    override fun current(scope: String): Double = guarded("current", 0.0) {
        stringRedisTemplate.opsForValue().get(key(scope))?.toDouble() ?: 0.0
    }

    override fun markOnce(flag: String): Boolean = guarded("markOnce", false) {
        stringRedisTemplate.opsForValue().setIfAbsent(key(flag), "1", RETENTION) == true
    }

    private fun key(scope: String) = "gw:budget:$scope"

    private fun <T> guarded(op: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        logger.warn("예산 카운터 {} 실패 — 통제 없이 통과: {}", op, e.message)
        fallback
    }

    companion object {
        // 일별 키 — 당일 + 여유 하루면 충분 (경계 시각의 이월 조회 대비)
        private val RETENTION: Duration = Duration.ofHours(48)
    }
}
