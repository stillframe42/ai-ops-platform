package stillframe42.llmgateway.cache

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
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

    /** 프로바이더 호출을 세는 스텁 — 캐시 적중이면 호출 수가 늘지 않아야 한다 */
    private class StubRelay(
        private val result: ChatCompletionResponse,
        registry: SimpleMeterRegistry,
    ) : ChatRelayService(ModelRouter(RoutingProperties()), emptyMap(), GatewayMetrics(registry)) {
        var calls = 0
        override fun relay(request: ChatCompletionRequest, route: Route): ChatCompletionResponse {
            calls++
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

    private fun service(
        relay: StubRelay,
        exactCache: ExactMatchCacheStore = InMemoryExactCache(),
        vectorStore: VectorStore? = null,
    ) = CachingChatService(
        router = ModelRouter(RoutingProperties()),
        relay = relay,
        exactCache = ExactResponseCache(exactCache, Duration.ofHours(1), mapper),
        semanticCache = SemanticResponseCache(vectorStore, 0.95, mapper),
        metrics = metrics,
    )

    @Test
    fun `동일 요청 반복은 정확 캐시 적중 - 프로바이더 재호출 없음`() {
        val relay = StubRelay(response(), registry)
        val svc = service(relay)

        val first = svc.complete(request(), taskType = null, cacheControl = null)
        val second = svc.complete(request(), taskType = null, cacheControl = null)

        assertEquals(CacheStatus.MISS, first.cacheStatus)
        assertEquals(CacheStatus.EXACT_HIT, second.cacheStatus)
        assertEquals(1, relay.calls)
        assertEquals("pong", second.response.choices.single().message.contentText())
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

        val result = svc.complete(request("heap 이 왜 올라가나"), taskType = null, cacheControl = null)

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

        val result = svc.complete(request(), taskType = null, cacheControl = null)

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

        svc.complete(request("heap 이 왜 올라가나"), taskType = null, cacheControl = null)

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

        svc.complete(request(), taskType = null, cacheControl = null)

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
        val result = svc.complete(toolRequest, taskType = null, cacheControl = null)

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
        val result = svc.complete(history, taskType = null, cacheControl = null)

        assertEquals(CacheStatus.BYPASS, result.cacheStatus)
        assertTrue(exactCache.map.isEmpty())
    }

    @Test
    fun `no-cache 헤더는 캐시를 우회하고 저장하지 않는다`() {
        val relay = StubRelay(response(), registry)
        val exactCache = InMemoryExactCache()
        val svc = service(relay, exactCache = exactCache)

        val result = svc.complete(request(), taskType = null, cacheControl = "no-cache")

        assertEquals(CacheStatus.BYPASS, result.cacheStatus)
        assertEquals(1, relay.calls)
        assertTrue(exactCache.map.isEmpty())
    }

    @Test
    fun `temperature 가 다르면 정확 캐시 키가 다르다`() {
        val relay = StubRelay(response(), registry)
        val exactCache = InMemoryExactCache()
        val svc = service(relay, exactCache = exactCache)

        svc.complete(request(temperature = 0.0), taskType = null, cacheControl = null)
        svc.complete(request(temperature = 0.7), taskType = null, cacheControl = null)

        assertEquals(2, relay.calls, "옵션이 다른 요청은 서로 다른 캐시 항목이다")
        assertEquals(2, exactCache.map.size)
        assertNotEquals(exactCache.map.keys.first(), exactCache.map.keys.last())
    }

    @Test
    fun `정확 캐시 저장소 장애 시 무캐시로 통과한다`() {
        val relay = StubRelay(response(), registry)
        val svc = service(relay, exactCache = FailingExactCache())

        val result = svc.complete(request(), taskType = null, cacheControl = null)

        assertEquals(CacheStatus.MISS, result.cacheStatus)
        assertEquals(1, relay.calls)
        assertEquals("pong", result.response.choices.single().message.contentText())
    }

    @Test
    fun `의미 캐시 미구성이면 정확 캐시만으로 동작한다`() {
        val relay = StubRelay(response(), registry)
        val svc = service(relay, vectorStore = null)

        val first = svc.complete(request(), taskType = null, cacheControl = null)
        val second = svc.complete(request(), taskType = null, cacheControl = null)

        assertEquals(CacheStatus.MISS, first.cacheStatus)
        assertEquals(CacheStatus.EXACT_HIT, second.cacheStatus)
    }

    @Test
    fun `캐시 적중 시 절감 토큰을 모델별로 기록한다`() {
        val relay = StubRelay(response(), registry)
        val svc = service(relay)

        svc.complete(request(), taskType = "root-cause-analysis", cacheControl = null)
        svc.complete(request(), taskType = "root-cause-analysis", cacheControl = null)

        val saved = registry.find("gateway.cache.saved.tokens")
            .tag("model", "claude-sonnet-5").tag("kind", "completion").counter()
        assertEquals(50.0, saved?.count())
        val hits = registry.find("gateway.cache.requests").tag("result", "exact_hit").counter()
        assertEquals(1.0, hits?.count())
    }
}
