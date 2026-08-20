package stillframe42.llmgateway.config

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

    @Bean
    fun budgetGuard(
        budgetProperties: BudgetProperties,
        budgetCounter: BudgetCounter,
        budgetAlerter: BudgetAlerter,
        gatewayMetrics: GatewayMetrics,
    ) = BudgetGuard(budgetProperties, budgetCounter, budgetAlerter, gatewayMetrics, Clock.systemUTC())
}
