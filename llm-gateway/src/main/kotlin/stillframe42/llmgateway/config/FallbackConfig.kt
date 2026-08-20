package stillframe42.llmgateway.config

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import stillframe42.llmgateway.fallback.FallbackProperties
import stillframe42.llmgateway.fallback.FallbackChatRelayService
import stillframe42.llmgateway.relay.ChatRelayService
import stillframe42.llmgateway.relay.GatewayMetrics

/** 폴백 체인 배선 (Phase 5) — 스타터 부재(Boot 4)로 서킷 브레이커를 코어 API 로 직접 조립 */
@Configuration
class FallbackConfig {

    @Bean
    fun circuitBreakerRegistry(fallbackProperties: FallbackProperties, meterRegistry: MeterRegistry): CircuitBreakerRegistry {
        val registry = CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom()
                .failureRateThreshold(fallbackProperties.circuit.failureRateThreshold)
                .waitDurationInOpenState(Duration.ofSeconds(fallbackProperties.circuit.waitInOpenSeconds))
                .slidingWindowSize(fallbackProperties.circuit.slidingWindowSize)
                .minimumNumberOfCalls(fallbackProperties.circuit.minimumCalls)
                // 클라이언트 잘못(400 경로)은 프로바이더 장애가 아니다 — 실패율 표본에서 제외
                .ignoreExceptions(IllegalArgumentException::class.java)
                .build(),
        )
        // resilience4j_circuitbreaker_state 게이지 노출 — 대시보드 서킷 상태 패널 원천 (Phase 6)
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry)
        return registry
    }

    @Bean
    fun fallbackChatRelayService(
        chatRelayService: ChatRelayService,
        fallbackProperties: FallbackProperties,
        circuitBreakerRegistry: CircuitBreakerRegistry,
        gatewayMetrics: GatewayMetrics,
    ) = FallbackChatRelayService(chatRelayService, fallbackProperties, circuitBreakerRegistry, gatewayMetrics)
}
