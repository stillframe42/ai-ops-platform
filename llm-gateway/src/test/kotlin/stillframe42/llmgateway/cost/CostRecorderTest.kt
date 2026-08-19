package stillframe42.llmgateway.cost

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import stillframe42.llmgateway.api.ChatChoice
import stillframe42.llmgateway.api.ChatCompletionResponse
import stillframe42.llmgateway.api.ChatMessage
import stillframe42.llmgateway.api.TokenUsage
import stillframe42.llmgateway.cache.CacheStatus
import stillframe42.llmgateway.relay.GatewayMetrics
import stillframe42.llmgateway.routing.Provider
import stillframe42.llmgateway.routing.Route

class CostRecorderTest {

    private val registry = SimpleMeterRegistry()
    private val ledger = RecordingLedger()
    private val recorder = CostRecorder(
        calculator = CostCalculator(
            CostProperties(prices = listOf(CostProperties.ModelPrice("claude-haiku-4-5", 1.0, 5.0))),
        ),
        ledger = ledger,
        metrics = GatewayMetrics(registry),
    )

    private val route = Route(taskType = "monitoring-summary", provider = Provider.ANTHROPIC, model = "claude-haiku-4-5", maxTokens = null)

    @Test
    fun `미스는 실비용을 원장과 메트릭에 기록한다`() {
        val cost = recorder.record("agent-service", route, response(), CacheStatus.MISS)

        // 입력 1M × $1 + 출력 1M × $5
        assertEquals(6.0, cost, 1e-9)
        val entry = ledger.entries.single()
        assertEquals("agent-service", entry.service)
        assertEquals("monitoring-summary", entry.task)
        assertEquals(6.0, entry.costUsd, 1e-9)
        assertEquals(0.0, entry.savedUsd, 1e-9)
        assertEquals(6.0, registry.get("gateway.cost.usd").tags("service", "agent-service").counter().count(), 1e-9)
    }

    @Test
    fun `캐시 히트는 지출 0·절감액으로 기록한다`() {
        val cost = recorder.record("agent-service", route, response(), CacheStatus.SEMANTIC_HIT)

        assertEquals(0.0, cost, 1e-9)
        val entry = ledger.entries.single()
        assertEquals(0.0, entry.costUsd, 1e-9)
        assertEquals(6.0, entry.savedUsd, 1e-9)
        assertEquals(6.0, registry.get("gateway.cost.saved.usd").counter().count(), 1e-9)
    }

    @Test
    fun `원장 비구성이어도 메트릭 기록은 동작한다`() {
        val recorderWithoutLedger = CostRecorder(
            calculator = CostCalculator(
                CostProperties(prices = listOf(CostProperties.ModelPrice("claude-haiku-4-5", 1.0, 5.0))),
            ),
            ledger = null,
            metrics = GatewayMetrics(registry),
        )

        val cost = recorderWithoutLedger.record("unknown", route, response(), CacheStatus.MISS)

        assertEquals(6.0, cost, 1e-9)
        assertTrue(ledger.entries.isEmpty())
    }

    private fun response() = ChatCompletionResponse(
        id = "chatcmpl-test",
        created = 0,
        model = "claude-haiku-4-5-20251001",
        choices = listOf(ChatChoice(index = 0, message = ChatMessage(role = "assistant", content = "ok"), finishReason = "end_turn")),
        usage = TokenUsage(promptTokens = 1_000_000, completionTokens = 1_000_000, totalTokens = 2_000_000),
    )

    private class RecordingLedger : CostLedger {
        val entries = mutableListOf<CostEntry>()
        override fun append(entry: CostEntry) {
            entries += entry
        }
    }
}
