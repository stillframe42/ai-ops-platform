package stillframe42.llmgateway.config

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.binder.MeterBinder
import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import stillframe42.llmgateway.budget.BudgetAlerter
import stillframe42.llmgateway.budget.BudgetCounter
import stillframe42.llmgateway.budget.BudgetGuard
import stillframe42.llmgateway.budget.BudgetProperties
import stillframe42.llmgateway.budget.RedisBudgetCounter
import stillframe42.llmgateway.relay.GatewayMetrics

/** 예산 통제 배선 (Phase 4) — 스캔이 못 하는 조립(Clock 값 파라미터·포트 선택)만 Config 에 */
@Configuration
class BudgetConfig {

    @Bean
    fun budgetCounter(stringRedisTemplate: StringRedisTemplate): BudgetCounter = RedisBudgetCounter(stringRedisTemplate)

    /**
     * 일 한도 게이지 (Phase 6) — 대시보드 "예산 대비 %"의 분모를 설정과 단일 원천으로 유지
     * (대시보드 상수 하드코딩이면 yml 변경 시 조용히 어긋난다). 한도 미설정 = 게이지 미등록 —
     * 0 노출은 "한도 0" 오독. 게이지 대상 객체는 싱글턴 프로퍼티 빈 (약참조 GC 방지)
     */
    @Bean
    fun budgetLimitMetrics(budgetProperties: BudgetProperties) = MeterBinder { registry ->
        budgetProperties.dailyLimitUsd?.let {
            Gauge.builder("gateway.budget.daily.limit.usd", budgetProperties) { p -> p.dailyLimitUsd ?: 0.0 }
                .register(registry)
        }
        budgetProperties.serviceDailyLimitUsd.keys.forEach { service ->
            Gauge.builder("gateway.budget.service.daily.limit.usd", budgetProperties) { p ->
                p.serviceDailyLimitUsd[service] ?: 0.0
            }.tag("service", service).register(registry)
        }
    }

    @Bean
    fun budgetGuard(
        budgetProperties: BudgetProperties,
        budgetCounter: BudgetCounter,
        budgetAlerter: BudgetAlerter,
        gatewayMetrics: GatewayMetrics,
    ) = BudgetGuard(budgetProperties, budgetCounter, budgetAlerter, gatewayMetrics, Clock.systemUTC())
}
