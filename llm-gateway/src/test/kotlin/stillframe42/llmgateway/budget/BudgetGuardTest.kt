package stillframe42.llmgateway.budget

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import stillframe42.llmgateway.relay.GatewayMetrics
import stillframe42.llmgateway.routing.Provider
import stillframe42.llmgateway.routing.Route

class BudgetGuardTest {

    private val counter = InMemoryBudgetCounter()
    private val alerter = RecordingAlerter()
    private val registry = SimpleMeterRegistry()
    private val guard = BudgetGuard(
        budgetProperties = BudgetProperties(
            dailyLimitUsd = 10.0,
            serviceDailyLimitUsd = mapOf("agent-service" to 4.0),
            downgrade = BudgetProperties.Downgrade(provider = "anthropic", model = "claude-haiku-4-5", maxTokens = 2000),
        ),
        budgetCounter = counter,
        budgetAlerter = alerter,
        gatewayMetrics = GatewayMetrics(registry),
        clock = Clock.fixed(Instant.parse("2026-08-19T03:00:00Z"), ZoneOffset.UTC),
    )

    private val sonnetRoute = Route(taskType = "root-cause-analysis", provider = Provider.ANTHROPIC, model = "claude-sonnet-5", maxTokens = null)

    @Test
    fun `한도 미달이면 라우트를 그대로 통과시킨다`() {
        val decision = guard.enforce(sonnetRoute, "agent-service")

        assertFalse(decision.downgraded)
        assertEquals(sonnetRoute, decision.route)
    }

    @Test
    fun `일 한도 도달이면 저비용 모델로 다운그레이드한다`() {
        guard.settle("control-plane", 10.0)

        val decision = guard.enforce(sonnetRoute, "agent-service")

        assertTrue(decision.downgraded)
        assertEquals("claude-haiku-4-5", decision.route.model)
        assertEquals("root-cause-analysis", decision.route.taskType)
        assertEquals(1.0, registry.get("gateway.budget.downgrades").counter().count(), 1e-9)
    }

    @Test
    fun `다운그레이드돼도 실험 variant 배정은 유지된다`() {
        guard.settle("control-plane", 10.0)

        val decision = guard.enforce(sonnetRoute.copy(variant = "analysis-model-haiku:B"), "agent-service")

        assertTrue(decision.downgraded)
        assertEquals("analysis-model-haiku:B", decision.route.variant)
    }

    @Test
    fun `서비스별 한도 도달이면 해당 서비스만 다운그레이드한다`() {
        guard.settle("agent-service", 4.0)

        assertTrue(guard.enforce(sonnetRoute, "agent-service").downgraded)
        assertFalse(guard.enforce(sonnetRoute, "control-plane").downgraded)
    }

    @Test
    fun `이미 다운그레이드 대상 모델인 요청은 그대로 둔다`() {
        guard.settle("agent-service", 10.0)
        val haikuRoute = Route(taskType = "monitoring-summary", provider = Provider.ANTHROPIC, model = "claude-haiku-4-5", maxTokens = 2000)

        assertFalse(guard.enforce(haikuRoute, "agent-service").downgraded)
    }

    @Test
    fun `80 퍼센트 최초 도달 시 한 번만 경고한다`() {
        guard.settle("agent-service", 3.0)
        assertTrue(alerter.messages.isEmpty())

        guard.settle("agent-service", 5.0) // 누계 8.0 = 80%
        guard.settle("agent-service", 0.5) // 여전히 80% 구간 — 중복 경고 금지

        assertEquals(1, alerter.messages.size)
        assertTrue(alerter.messages.single().contains("80%"))
    }

    @Test
    fun `100 퍼센트 도달 시 다운그레이드 전환을 경고한다`() {
        guard.settle("agent-service", 8.0)
        guard.settle("agent-service", 2.0)

        assertEquals(2, alerter.messages.size) // 80% 1건 + 100% 1건
        assertTrue(alerter.messages.last().contains("다운그레이드"))
    }

    @Test
    fun `일 한도 미설정이면 예산 통제는 비활성이다`() {
        val disabled = BudgetGuard(
            budgetProperties = BudgetProperties(dailyLimitUsd = null),
            budgetCounter = counter,
            budgetAlerter = alerter,
            gatewayMetrics = GatewayMetrics(registry),
            clock = Clock.systemUTC(),
        )
        disabled.settle("agent-service", 999.0)

        assertFalse(disabled.enforce(sonnetRoute, "agent-service").downgraded)
        assertTrue(alerter.messages.isEmpty())
    }

    private class InMemoryBudgetCounter : BudgetCounter {
        private val totals = mutableMapOf<String, Double>()
        private val flags = mutableSetOf<String>()

        override fun add(scope: String, amount: Double): Double {
            val next = (totals[scope] ?: 0.0) + amount
            totals[scope] = next
            return next
        }

        override fun current(scope: String): Double = totals[scope] ?: 0.0

        override fun markOnce(flag: String): Boolean = flags.add(flag)
    }

    private class RecordingAlerter : BudgetAlerter {
        val messages = mutableListOf<String>()
        override fun alert(message: String) {
            messages += message
        }
    }
}
