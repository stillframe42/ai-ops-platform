package stillframe42.controlplane.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.kafka.listener.CommonErrorHandler
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.ExponentialBackOff

/**
 * 컨슈머 공통 에러 핸들러 — 기본값(FixedBackOff 0ms×9회)은 재시도 창이 사실상 0초라
 * DB 재기동(수십 초~수 분)을 못 버틴다 (DAY 19 실측: 소진 후 건너뜀 = 유실).
 * 지수 backoff 1s→60s 상한, 총 12회 ≈ 7분 창으로 교체. 소진 후 건너뜀은 유지 (파티션 무한 정지 방지).
 * 데이터 기인 예외(무결성 위반)는 재시도 무익 — 즉시 건너뜀.
 */
@Configuration
class KafkaConsumerConfig {

    @Bean
    fun kafkaErrorHandler(): CommonErrorHandler =
        DefaultErrorHandler(
            ExponentialBackOff(INITIAL_INTERVAL_MS, 2.0).apply {
                maxInterval = MAX_INTERVAL_MS
                maxAttempts = MAX_ATTEMPTS
            },
        ).apply {
            addNotRetryableExceptions(DataIntegrityViolationException::class.java)
        }

    companion object {
        private const val INITIAL_INTERVAL_MS = 1_000L
        private const val MAX_INTERVAL_MS = 60_000L
        private const val MAX_ATTEMPTS = 12L
    }
}
