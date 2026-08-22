package stillframe42.llmgateway.cache

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.ai.document.Document
import org.springframework.ai.document.MetadataMode
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.filter.Filter
import stillframe42.llmgateway.api.ChatChoice
import stillframe42.llmgateway.api.ChatCompletionRequest
import stillframe42.llmgateway.api.ChatCompletionResponse
import stillframe42.llmgateway.api.ChatMessage
import stillframe42.llmgateway.api.FunctionSpec
import stillframe42.llmgateway.api.TokenUsage
import stillframe42.llmgateway.api.ToolSpec
import stillframe42.llmgateway.budget.BudgetCounter
import stillframe42.llmgateway.budget.BudgetGuard
import stillframe42.llmgateway.budget.BudgetProperties
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import stillframe42.llmgateway.cost.CostCalculator
import stillframe42.llmgateway.fallback.FallbackProperties
import stillframe42.llmgateway.fallback.FallbackChatRelayService
import stillframe42.llmgateway.routing.Provider
import stillframe42.llmgateway.cost.CostProperties
import stillframe42.llmgateway.cost.CostRecorder
import stillframe42.llmgateway.relay.ChatRelayService
import stillframe42.llmgateway.relay.GatewayMetrics
import stillframe42.llmgateway.routing.ModelRouter
import stillframe42.llmgateway.routing.Route
import stillframe42.llmgateway.routing.RoutingProperties
import tools.jackson.module.kotlin.jacksonObjectMapper

class CachingChatServiceTest {

    private val registry = SimpleMeterRegistry()
    private val metrics = GatewayMetrics(registry)
    private val mapper = jacksonObjectMapper()

    private fun response(text: String = "pong", model: String = "claude-sonnet-5") = ChatCompletionResponse(
        id = "chatcmpl-test",
        created = 1_755_500_000,
        model = model,
        choices = listOf(ChatChoice(index = 0, message = ChatMessage("assistant", text), finishReason = "end_turn")),
        usage = TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150),
    )

    private fun request(text: String = "heap 이 왜 올라가나", temperature: Double? = null) = ChatCompletionRequest(
        messages = listOf(ChatMessage("user", text)),
        temperature = temperature,
    )

    /** 프로바이더 호출을 세는 스텁 — 캐시 적중이면 호출 수가 늘지 않아야 한다. failProviders 는 장애 모사 */
    private class StubRelay(
        private val result: ChatCompletionResponse,
        registry: SimpleMeterRegistry,
        private val failProviders: Set<Provider> = emptySet(),
    ) : ChatRelayService(ModelRouter(RoutingProperties()), emptyMap(), GatewayMetrics(registry)) {
        var calls = 0
        var lastRoute: Route? = null
        override fun relay(request: ChatCompletionRequest, route: Route): ChatCompletionResponse {
            calls++
            lastRoute = route
            if (route.provider in failProviders) throw IllegalStateException("${route.provider} 장애 모사")
            return result
        }
    }

    private class InMemoryExactCache : ExactMatchCacheStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String, ttl: Duration) {
            map[key] = value
        }
    }

    private class FailingExactCache : ExactMatchCacheStore {
        override fun get(key: String): String? = throw IllegalStateException("redis down")
        override fun put(key: String, value: String, ttl: Duration) = throw IllegalStateException("redis down")
    }

    private class RecordingVectorStore(private val hit: Document? = null) : VectorStore {
        val added = mutableListOf<Document>()
        var lastSearch: SearchRequest? = null
        override fun add(documents: List<Document>) {
            added += documents
        }
        override fun delete(idList: List<String>) = Unit
        override fun delete(filterExpression: Filter.Expression) = Unit
        override fun similaritySearch(request: SearchRequest): List<Document> {
            lastSearch = request
            return listOfNotNull(hit)
        }
    }

    private class InMemoryBudgetCounter : BudgetCounter {
        val totals = mutableMapOf<String, Double>()
        private val flags = mutableSetOf<String>()
        override fun add(scope: String, amount: Double): Double {
            val next = (totals[scope] ?: 0.0) + amount
            totals[scope] = next
            return next
        }
        override fun current(scope: String): Double = totals[scope] ?: 0.0
        override fun markOnce(flag: String): Boolean = flags.add(flag)
    }

    private fun service(
        relay: StubRelay,
        exactCache: ExactMatchCacheStore = InMemoryExactCache(),
        vectorStore: VectorStore? = null,
        budgetLimitUsd: Double? = null,
        budgetCounter: BudgetCounter = InMemoryBudgetCounter(),
        prices: List<CostProperties.ModelPrice> = emptyList(),
    ) = CachingChatService(
        modelRouter = ModelRouter(RoutingProperties()),
        fallbackChatRelayService = FallbackChatRelayService(relay, FallbackProperties(), CircuitBreakerRegistry.ofDefaults(), metrics),
        exactResponseCache = ExactResponseCache(exactCache, Duration.ofHours(1), mapper),
        semanticResponseCache = SemanticResponseCache(vectorStore, 0.95, mapper),
        budgetGuard = BudgetGuard(
            budgetProperties = BudgetProperties(dailyLimitUsd = budgetLimitUsd),
            budgetCounter = budgetCounter,
            budgetAlerter = { },
            gatewayMetrics = metrics,
            clock = Clock.systemUTC(),
        ),
        costRecorder = CostRecorder(CostCalculator(CostProperties(prices = prices)), costLedger = null, gatewayMetrics = metrics),
        gatewayMetrics = metrics,
    )

    @Test
    fun `동일 요청 반복은 정확 캐시 적중 - 프로바이더 재호출 없음`() {
        val relay = StubRelay(response(), registry)
        val svc = service(relay)

        val first = svc.complete(request(), taskType = null, cacheControl = null, service = "agent-service")
        val second = svc.complete(request(), taskType = null, cacheControl = null, service = "agent-service")

        assertEquals(CacheStatus.MISS, first.cacheStatus)
        assertEquals(CacheStatus.EXACT_HIT, second.cacheStatus)
        assertEquals(1, relay.calls)
        assertEquals("pong", second.response.choices.single().message.contentText())
    }

    @Test
    fun `레이턴시 타이머는 캐시 판정별로 분리 기록된다 - 분위수 대시보드 원천`() {
        val relay = StubRelay(response(), registry)
        val svc = service(relay)

        svc.complete(request(), taskType = null, cacheControl = null, service = "agent-service")
        svc.complete(request(), taskType = null, cacheControl = null, service = "agent-service")

        // 버킷 발행 검증은 GatewayMetricsTest (Prometheus 노출 형식) — Simple 레지스트리는 버킷 미실체화
        assertEquals(1, registry.get("gateway.latency").tag("result", "miss").timer().count())
        assertEquals(1, registry.get("gateway.latency").tag("result", "exact_hit").timer().count())
    }

    @Test
    fun `정확 캐시 미스에 의미 캐시 유사 응답이 있으면 반환하고 정확 캐시로 승격한다`() {
        val relay = StubRelay(response(), registry)
        val cachedJson = mapper.writeValueAsString(response(text = "이전에 계산한 분석"))
        val exactCache = InMemoryExactCache()
        val semanticStore = RecordingVectorStore(
            hit = Document("user: heap 사용률이 왜 상승하나", mapOf("model" to "claude-sonnet-5", "response" to cachedJson)),
        )
        val svc = service(relay, exactCache = exactCache, vectorStore = semanticStore)

        val result = svc.complete(request("heap 이 왜 올라가나"), taskType = null, cacheControl = null, service = "agent-service")

        assertEquals(CacheStatus.SEMANTIC_HIT, result.cacheStatus)
        assertEquals(0, relay.calls)
        assertEquals("이전에 계산한 분석", result.response.choices.single().message.contentText())
        assertTrue(exactCache.map.isNotEmpty(), "의미 캐시 히트는 정확 캐시로 승격되어야 한다")
    }

    @Test
    fun `미스면 프로바이더 호출 후 정확 캐시와 의미 캐시에 저장한다`() {
        val relay = StubRelay(response(), registry)
        val exactCache = InMemoryExactCache()
        val semanticStore = RecordingVectorStore()
        val svc = service(relay, exactCache = exactCache, vectorStore = semanticStore)

        val result = svc.complete(request(), taskType = null, cacheControl = null, service = "agent-service")

        assertEquals(CacheStatus.MISS, result.cacheStatus)
        assertEquals(1, relay.calls)
        assertEquals(1, exactCache.map.size)
        val doc = semanticStore.added.single()
        assertEquals("claude-sonnet-5", doc.metadata["model"])
        assertTrue((doc.metadata["response"] as String).contains("pong"))
    }

    @Test
    fun `의미 캐시 저장 문서의 임베딩 대상 텍스트에 metadata 가 섞이지 않는다 - 임베딩 공간 분리 방지`() {
        val relay = StubRelay(response(), registry)
        val semanticStore = RecordingVectorStore()
        val svc = service(relay, vectorStore = semanticStore)

        svc.complete(request("heap 이 왜 올라가나"), taskType = null, cacheControl = null, service = "agent-service")

        // OpenAiEmbeddingModel 기본 MetadataMode.EMBED 는 저장 시 metadata 를 임베딩 텍스트에 포함한다 —
        // 검색은 질의 텍스트만 임베딩하므로 섞이면 같은 문장끼리도 거리 0.30 (DAY 31 실측).
        // 기본 템플릿의 "\n\n" 접두조차 거리 0.037 을 소모(임계 예산 0.05 의 74%)하므로 정확 일치가 요건
        val doc = semanticStore.added.single()
        assertEquals(doc.text, doc.getFormattedContent(MetadataMode.EMBED))
    }

    @Test
    fun `의미 캐시 검색은 해석된 모델 필터와 유사도 임계값을 적용한다`() {
        val relay = StubRelay(response(), registry)
        val semanticStore = RecordingVectorStore()
        val svc = service(relay, vectorStore = semanticStore)

        svc.complete(request(), taskType = null, cacheControl = null, service = "agent-service")

        val search = semanticStore.lastSearch!!
        assertEquals(0.95, search.similarityThreshold)
        assertEquals(1, search.topK)
        assertTrue(search.filterExpression.toString().contains("claude-sonnet-5"))
    }

    @Test
    fun `tool 정의 포함 요청은 캐시를 우회한다`() {
        val relay = StubRelay(response(), registry)
        val exactCache = InMemoryExactCache()
        val semanticStore = RecordingVectorStore()
        val svc = service(relay, exactCache = exactCache, vectorStore = semanticStore)

        val toolRequest = ChatCompletionRequest(
            messages = listOf(ChatMessage("user", "메트릭 조회")),
            tools = listOf(ToolSpec(function = FunctionSpec(name = "query_prometheus"))),
        )
        val result = svc.complete(toolRequest, taskType = null, cacheControl = null, service = "agent-service")

        assertEquals(CacheStatus.BYPASS, result.cacheStatus)
        assertTrue(exactCache.map.isEmpty(), "우회 요청은 저장하지 않는다")
        assertTrue(semanticStore.added.isEmpty())
        assertNull(semanticStore.lastSearch)
    }

    @Test
    fun `tool 이력이 있는 대화도 캐시를 우회한다`() {
        val relay = StubRelay(response(), registry)
        val exactCache = InMemoryExactCache()
        val svc = service(relay, exactCache = exactCache)

        val history = ChatCompletionRequest(
            messages = listOf(
                ChatMessage("user", "로그 확인"),
                ChatMessage("tool", "로그 3건", toolCallId = "tc_1"),
            ),
        )
        val result = svc.complete(history, taskType = null, cacheControl = null, service = "agent-service")

        assertEquals(CacheStatus.BYPASS, result.cacheStatus)
        assertTrue(exactCache.map.isEmpty())
    }

    @Test
    fun `no-cache 헤더는 캐시를 우회하고 저장하지 않는다`() {
        val relay = StubRelay(response(), registry)
        val exactCache = InMemoryExactCache()
        val svc = service(relay, exactCache = exactCache)

        val result = svc.complete(request(), taskType = null, cacheControl = "no-cache", service = "agent-service")

        assertEquals(CacheStatus.BYPASS, result.cacheStatus)
        assertEquals(1, relay.calls)
        assertTrue(exactCache.map.isEmpty())
    }

    @Test
    fun `temperature 가 다르면 정확 캐시 키가 다르다`() {
        val relay = StubRelay(response(), registry)
        val exactCache = InMemoryExactCache()
        val svc = service(relay, exactCache = exactCache)

        svc.complete(request(temperature = 0.0), taskType = null, cacheControl = null, service = "agent-service")
        svc.complete(request(temperature = 0.7), taskType = null, cacheControl = null, service = "agent-service")

        assertEquals(2, relay.calls, "옵션이 다른 요청은 서로 다른 캐시 항목이다")
        assertEquals(2, exactCache.map.size)
        assertNotEquals(exactCache.map.keys.first(), exactCache.map.keys.last())
    }

    @Test
    fun `정확 캐시 저장소 장애 시 무캐시로 통과한다`() {
        val relay = StubRelay(response(), registry)
        val svc = service(relay, exactCache = FailingExactCache())

        val result = svc.complete(request(), taskType = null, cacheControl = null, service = "agent-service")

        assertEquals(CacheStatus.MISS, result.cacheStatus)
        assertEquals(1, relay.calls)
        assertEquals("pong", result.response.choices.single().message.contentText())
    }

    @Test
    fun `의미 캐시 미구성이면 정확 캐시만으로 동작한다`() {
        val relay = StubRelay(response(), registry)
        val svc = service(relay, vectorStore = null)

        val first = svc.complete(request(), taskType = null, cacheControl = null, service = "agent-service")
        val second = svc.complete(request(), taskType = null, cacheControl = null, service = "agent-service")

        assertEquals(CacheStatus.MISS, first.cacheStatus)
        assertEquals(CacheStatus.EXACT_HIT, second.cacheStatus)
    }

    @Test
    fun `예산 한도 도달 시 다운그레이드 라우트로 중계하고 결과에 표시한다`() {
        val relay = StubRelay(response(model = "claude-haiku-4-5"), registry)
        val counter = InMemoryBudgetCounter()
        counter.add("total:${LocalDate.now(Clock.systemUTC())}", 10.0)
        val svc = service(relay, budgetLimitUsd = 10.0, budgetCounter = counter)

        val result = svc.complete(request(), taskType = "root-cause-analysis", cacheControl = null, service = "agent-service")

        assertTrue(result.downgraded)
        assertEquals("claude-haiku-4-5", relay.lastRoute!!.model, "중계는 다운그레이드된 라우트를 사용해야 한다")
    }

    @Test
    fun `미스의 실비용이 예산 카운터에 정산된다`() {
        val relay = StubRelay(response(model = "claude-sonnet-5"), registry)
        val counter = InMemoryBudgetCounter()
        val svc = service(
            relay,
            budgetLimitUsd = 10.0,
            budgetCounter = counter,
            prices = listOf(CostProperties.ModelPrice("claude-sonnet-5", 3.0, 15.0)),
        )

        svc.complete(request(), taskType = null, cacheControl = null, service = "agent-service")

        // 입력 100 × $3/MTok + 출력 50 × $15/MTok
        val expected = 100 * 3.0 / 1_000_000 + 50 * 15.0 / 1_000_000
        assertEquals(expected, counter.current("total:${LocalDate.now(Clock.systemUTC())}"), 1e-12)
        assertEquals(expected, counter.current("service:agent-service:${LocalDate.now(Clock.systemUTC())}"), 1e-12)
    }

    @Test
    fun `폴백 응답은 캐시에 저장하지 않고 결과에 폴백 대상을 표시한다`() {
        val relay = StubRelay(response(model = "gpt-5.6-terra"), registry, failProviders = setOf(Provider.ANTHROPIC))
        val exactCache = InMemoryExactCache()
        val semanticStore = RecordingVectorStore()
        val svc = service(relay, exactCache = exactCache, vectorStore = semanticStore)

        val result = svc.complete(request(), taskType = null, cacheControl = null, service = "agent-service")

        assertEquals("openai", result.fallbackTarget)
        assertEquals("gpt-5.6-terra", result.response.model)
        assertTrue(exactCache.map.isEmpty(), "장애 중 생성물이 정상 캐시를 오염하지 않아야 한다")
        assertTrue(semanticStore.added.isEmpty())
    }

    @Test
    fun `폴백 시 비용은 실사용 라우트의 단가로 정산된다`() {
        val relay = StubRelay(response(model = "gpt-5.6-terra"), registry, failProviders = setOf(Provider.ANTHROPIC))
        val counter = InMemoryBudgetCounter()
        val svc = service(
            relay,
            budgetLimitUsd = 10.0,
            budgetCounter = counter,
            prices = listOf(
                CostProperties.ModelPrice("claude-sonnet-5", 3.0, 15.0),
                CostProperties.ModelPrice("gpt-5.6-terra", 2.0, 12.0),
            ),
        )

        svc.complete(request(), taskType = null, cacheControl = null, service = "agent-service")

        // 입력 100 × $2/MTok + 출력 50 × $12/MTok — 원 라우트(sonnet)가 아닌 폴백 모델 단가
        val expected = 100 * 2.0 / 1_000_000 + 50 * 12.0 / 1_000_000
        assertEquals(expected, counter.current("total:${LocalDate.now(Clock.systemUTC())}"), 1e-12)
    }

    @Test
    fun `캐시 적중 시 절감 토큰을 모델별로 기록한다`() {
        val relay = StubRelay(response(), registry)
        val svc = service(relay)

        svc.complete(request(), taskType = "root-cause-analysis", cacheControl = null, service = "agent-service")
        svc.complete(request(), taskType = "root-cause-analysis", cacheControl = null, service = "agent-service")

        val saved = registry.find("gateway.cache.saved.tokens")
            .tag("model", "claude-sonnet-5").tag("kind", "completion").counter()
        assertEquals(50.0, saved?.count())
        val hits = registry.find("gateway.cache.requests").tag("result", "exact_hit").counter()
        assertEquals(1.0, hits?.count())
    }
}
