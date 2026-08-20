package stillframe42.llmgateway.fallback

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import stillframe42.llmgateway.api.ChatChoice
import stillframe42.llmgateway.api.ChatCompletionRequest
import stillframe42.llmgateway.api.ChatCompletionResponse
import stillframe42.llmgateway.api.ChatMessage
import stillframe42.llmgateway.api.TokenUsage
import stillframe42.llmgateway.relay.ChatRelayService
import stillframe42.llmgateway.relay.GatewayMetrics
import stillframe42.llmgateway.routing.ModelRouter
import stillframe42.llmgateway.routing.Provider
import stillframe42.llmgateway.routing.Route
import stillframe42.llmgateway.routing.RoutingProperties

class FallbackChatRelayServiceTest {

    private val registry = SimpleMeterRegistry()
    private val metrics = GatewayMetrics(registry)

    private fun response(model: String) = ChatCompletionResponse(
        id = "chatcmpl-test",
        created = 1_755_600_000,
        model = model,
        choices = listOf(ChatChoice(index = 0, message = ChatMessage("assistant", "pong"), finishReason = "stop")),
        usage = TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
    )

    private fun request() = ChatCompletionRequest(messages = listOf(ChatMessage("user", "ping")))

    private val anthropicRoute = Route("root-cause-analysis", Provider.ANTHROPIC, "claude-sonnet-5", 2000)
    private val openaiRoute = Route("code-review-critical", Provider.OPENAI, "gpt-5.6-terra", 2000)

    /** 라우트별 성공/실패를 지시하는 스텁 — 프로바이더 호출 이력을 라우트로 기록한다 */
    private class ScriptedRelay(
        registry: SimpleMeterRegistry,
        private val behavior: (Route) -> ChatCompletionResponse,
    ) : ChatRelayService(ModelRouter(RoutingProperties()), emptyMap(), GatewayMetrics(registry)) {
        val routes = mutableListOf<Route>()
        override fun relay(request: ChatCompletionRequest, route: Route): ChatCompletionResponse {
            routes += route
            return behavior(route)
        }
    }

    private fun breakerRegistry(minimumCalls: Int = 100): CircuitBreakerRegistry = CircuitBreakerRegistry.of(
        CircuitBreakerConfig.custom()
            .failureRateThreshold(50f)
            .slidingWindowSize(10)
            .minimumNumberOfCalls(minimumCalls)
            .ignoreExceptions(IllegalArgumentException::class.java)
            .build(),
    )

    private fun resilient(primary: ChatRelayService, breakers: CircuitBreakerRegistry = breakerRegistry()) =
        FallbackChatRelayService(primary, FallbackProperties(), breakers, metrics)

    @Test
    fun `주 중계가 성공하면 폴백 없이 그대로 반환한다`() {
        val primary = ScriptedRelay(registry) { response("claude-sonnet-5") }
        val outcome = resilient(primary).relay(request(), anthropicRoute)

        assertEquals(FallbackStatus.NONE, outcome.fallback)
        assertEquals(anthropicRoute, outcome.route)
        assertEquals("claude-sonnet-5", outcome.response.model)
        assertEquals(listOf(anthropicRoute), primary.routes)
    }

    @Test
    fun `주 중계 실패 시 폴백 프로바이더로 재중계한다 - 태스크 유지, 모델 교체`() {
        val primary = ScriptedRelay(registry) { route ->
            if (route.provider == Provider.ANTHROPIC) throw IllegalStateException("anthropic 529")
            response("gpt-5.6-terra")
        }
        val outcome = resilient(primary).relay(request(), anthropicRoute)

        assertEquals(FallbackStatus.PROVIDER, outcome.fallback)
        assertEquals(Provider.OPENAI, outcome.route.provider)
        assertEquals("gpt-5.6-terra", outcome.route.model)
        assertEquals("root-cause-analysis", outcome.route.taskType, "폴백 라우트는 태스크 차원을 유지한다")
        assertEquals<Int?>(2000, outcome.route.maxTokens, "원 라우트의 유효 max_tokens 승계")
    }

    @Test
    fun `폴백까지 실패하면 로컬 폴백 응답을 반환한다`() {
        val primary = ScriptedRelay(registry) { throw IllegalStateException("전 프로바이더 장애") }
        val outcome = resilient(primary).relay(request(), anthropicRoute)

        assertEquals(FallbackStatus.LOCAL, outcome.fallback)
        assertEquals(FallbackChatRelayService.LOCAL_FALLBACK_MODEL, outcome.response.model)
        assertEquals("stop", outcome.response.choices.single().finishReason)
        assertEquals(0, outcome.response.usage.totalTokens, "로컬 폴백은 토큰 소비가 없다")
    }

    @Test
    fun `주 라우트가 이미 폴백 프로바이더면 재중계 없이 로컬 폴백으로 간다`() {
        val primary = ScriptedRelay(registry) { throw IllegalStateException("openai 5xx") }
        val outcome = resilient(primary).relay(request(), openaiRoute)

        assertEquals(FallbackStatus.LOCAL, outcome.fallback)
        assertEquals(1, primary.routes.size, "같은 프로바이더 재시도는 무의미 — 호출 1회로 끝낸다")
    }

    @Test
    fun `클라이언트 잘못 - IllegalArgumentException 은 폴백 없이 그대로 전파한다`() {
        val primary = ScriptedRelay(registry) { throw IllegalArgumentException("지원하지 않는 role: developer") }
        assertFailsWith<IllegalArgumentException> {
            resilient(primary).relay(request(), anthropicRoute)
        }
        assertEquals(1, primary.routes.size, "어느 프로바이더로도 같은 실패 — 재중계하지 않는다")
    }

    @Test
    fun `실패율 임계 초과로 서킷이 열리면 주 중계 호출 없이 즉시 폴백한다`() {
        val primary = ScriptedRelay(registry) { route ->
            if (route.provider == Provider.ANTHROPIC) throw IllegalStateException("anthropic 다운")
            response("gpt-5.6-terra")
        }
        val relay = resilient(primary, breakerRegistry(minimumCalls = 4))

        repeat(5) { relay.relay(request(), anthropicRoute) }

        val anthropicCalls = primary.routes.count { it.provider == Provider.ANTHROPIC }
        assertEquals(4, anthropicCalls, "최소 표본 4회 전부 실패(100%) 후 서킷 오픈 — 5번째는 주 중계 생략")
    }

    @Test
    fun `폴백 발생이 대상별로 메트릭에 기록된다`() {
        val primary = ScriptedRelay(registry) { route ->
            if (route.provider == Provider.ANTHROPIC) throw IllegalStateException("anthropic 다운")
            response("gpt-5.6-terra")
        }
        resilient(primary).relay(request(), anthropicRoute)
        resilient(ScriptedRelay(registry) { throw IllegalStateException("전 장애") }).relay(request(), anthropicRoute)

        val toProvider = registry.find("gateway.fallback").tag("target", "openai").counter()
        val toLocal = registry.find("gateway.fallback").tag("target", "local").counter()
        assertEquals(1.0, toProvider?.count())
        assertEquals(1.0, toLocal?.count())
    }
}
