package stillframe42.llmgateway.cache

import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import stillframe42.llmgateway.anyNonNull
import stillframe42.llmgateway.api.ChatCompletionRequest
import stillframe42.llmgateway.api.ChatMessage
import stillframe42.llmgateway.routing.Provider
import stillframe42.llmgateway.routing.Route
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** RT-13 (의미 캐시 대체)의 판정 기준을 결정론으로 고정한다 — 숫자 토큰 열이 다른 질의는 히트 불인정. */
class SemanticResponseCacheTest {

    private val mapper = JsonMapper.builder().build()
    private val vectorStore = mock(VectorStore::class.java)
    private val cache = SemanticResponseCache(vectorStore, similarityThreshold = 0.95, objectMapper = mapper)

    private val route = Route(taskType = "monitoring-summary", provider = Provider.ANTHROPIC, model = "claude-haiku-4-5", maxTokens = 256)

    private val responseJson =
        """{"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"claude-haiku-4-5",
           "choices":[{"index":0,"message":{"role":"assistant","content":"P3"},"finish_reason":"stop"}],
           "usage":{"prompt_tokens":10,"completion_tokens":1,"total_tokens":11}}"""

    private fun request(text: String) = ChatCompletionRequest(messages = listOf(ChatMessage(role = "user", content = text)))

    private fun storedDocument(text: String) = Document("doc-1", "user: $text", mapOf("model" to route.model, "response" to responseJson))

    @Test
    fun `숫자 토큰이 다른 유사 질의는 히트 불인정 - RT-13 대체 차단`() {
        // 임베딩 유사도는 임계를 넘겼다고 가정 (검색이 문서를 돌려준 상황) — 필터는 그 뒤의 결정론 단계
        given(vectorStore.similaritySearch(anyNonNull<SearchRequest>()))
            .willReturn(listOf(storedDocument("target-app 의 5xx 에러율이 12% 다. 심각도를 P1/P2/P3 중 하나로만 답하라.")))

        val result = cache.findSimilar(request("target-app 의 5xx 에러율이 2% 다. 심각도를 P1/P2/P3 중 하나로만 답하라."), route)

        assertNull(result, "숫자가 다른 질의에 저장 응답이 대체됐다 (RT-13)")
    }

    @Test
    fun `숫자 토큰까지 같은 질의는 히트 유지 - 같은 수치 반복 질의 캐시는 보존`() {
        given(vectorStore.similaritySearch(anyNonNull<SearchRequest>()))
            .willReturn(listOf(storedDocument("target-app 의 5xx 에러율이 12% 다. 심각도를 P1/P2/P3 중 하나로만 답하라.")))

        val result = cache.findSimilar(request("target-app 의 5xx 에러율이 12% 다. 심각도를 P1/P2/P3 중 하나로만 답하라."), route)

        assertEquals("chatcmpl-1", result?.id)
    }

    @Test
    fun `숫자가 없는 질의 쌍은 필터에 걸리지 않는다 - 기존 의미 히트 동작 무변경`() {
        given(vectorStore.similaritySearch(anyNonNull<SearchRequest>()))
            .willReturn(listOf(storedDocument("target-app 이 정상 동작 중인지 요약하라.")))

        val result = cache.findSimilar(request("target-app 이 정상 동작하는지 요약해 달라."), route)

        assertEquals("chatcmpl-1", result?.id)
    }
}
