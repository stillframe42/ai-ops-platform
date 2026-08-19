package stillframe42.llmgateway.config

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import stillframe42.llmgateway.ratelimit.Bucket4jRedisRateLimiter
import stillframe42.llmgateway.ratelimit.RateLimitInterceptor
import stillframe42.llmgateway.ratelimit.RateLimitProperties
import stillframe42.llmgateway.ratelimit.RateLimiter
import stillframe42.llmgateway.relay.GatewayMetrics
import tools.jackson.databind.ObjectMapper

/**
 * Rate Limiting 배선 (Phase 4) — default-rpm 구성 시에만 활성 (키-게이트 관례).
 * Bucket4j 의 Lettuce 통합은 바이트 코덱 원시 연결을 요구해 Spring 의 RedisTemplate 연결을
 * 재사용하지 못한다 — 접속 정보만 공유하는 전용 RedisClient 를 둔다.
 */
@Configuration
class RateLimitConfig {

    @Configuration
    @ConditionalOnProperty("gateway.ratelimit.default-rpm")
    class Enabled {

        @Bean(destroyMethod = "shutdown")
        fun rateLimitRedisClient(
            @Value("\${spring.data.redis.host:localhost}") host: String,
            @Value("\${spring.data.redis.port:6379}") port: Int,
        ): RedisClient = RedisClient.create(RedisURI.create(host, port))

        @Bean
        fun rateLimiter(rateLimitRedisClient: RedisClient, properties: RateLimitProperties): RateLimiter =
            Bucket4jRedisRateLimiter(rateLimitRedisClient, properties)

        // 비활성이면 이 Config 자체가 없어 인터셉터도 등록되지 않는다.
        // 익명 WebMvcConfigurer 빈 — 클래스로 두면 @WebMvcTest 슬라이스가 조건 무시하고 포함한다
        @Bean
        fun rateLimitWebConfigurer(
            rateLimiter: RateLimiter,
            metrics: GatewayMetrics,
            mapper: ObjectMapper,
        ): WebMvcConfigurer = object : WebMvcConfigurer {
            override fun addInterceptors(registry: InterceptorRegistry) {
                registry.addInterceptor(RateLimitInterceptor(rateLimiter, metrics, mapper)).addPathPatterns("/v1/**")
            }
        }
    }
}
