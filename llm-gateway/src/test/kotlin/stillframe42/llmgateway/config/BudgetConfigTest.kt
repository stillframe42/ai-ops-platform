package stillframe42.llmgateway.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import stillframe42.llmgateway.budget.BudgetProperties

class BudgetConfigTest {

    private val registry = SimpleMeterRegistry()

    @Test
    fun `일 한도가 게이지로 노출된다 - 대시보드 예산 대비 퍼센트의 분모`() {
        val properties = BudgetProperties(dailyLimitUsd = 5.0, serviceDailyLimitUsd = mapOf("agent-service" to 4.0))

        BudgetConfig().budgetLimitMetrics(properties).bindTo(registry)

        assertEquals(5.0, registry.get("gateway.budget.daily.limit.usd").gauge().value())
        assertEquals(
            4.0,
            registry.get("gateway.budget.service.daily.limit.usd").tag("service", "agent-service").gauge().value(),
        )
    }

    @Test
    fun `한도 미설정이면 게이지도 없다 - 0 노출은 "한도 0" 오독을 만든다`() {
        BudgetConfig().budgetLimitMetrics(BudgetProperties()).bindTo(registry)

        assertNull(registry.find("gateway.budget.daily.limit.usd").gauge())
    }
}
