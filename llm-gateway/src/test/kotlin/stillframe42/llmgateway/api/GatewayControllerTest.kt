package stillframe42.llmgateway.api

import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import stillframe42.llmgateway.cache.CacheStatus
import stillframe42.llmgateway.cache.CachedChatResult
import stillframe42.llmgateway.cache.CachingChatService
import stillframe42.llmgateway.relay.EmbeddingRelayService

@WebMvcTest(GatewayController::class)
class GatewayControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var cachingChat: CachingChatService

    @MockitoBean
    lateinit var embeddingRelay: EmbeddingRelayService

    private fun response() = ChatCompletionResponse(
        id = "chatcmpl-test",
        created = 1_755_400_000,
        model = "claude-sonnet-5",
        choices = listOf(
            ChatChoice(index = 0, message = ChatMessage("assistant", "pong"), finishReason = "end_turn"),
        ),
        usage = TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
    )

    @Test
    fun `채팅 완성 응답은 OpenAI 계약 형태 - snake_case usage 포함`() {
        val request = ChatCompletionRequest(
            model = "claude-sonnet-5",
            messages = listOf(ChatMessage(role = "user", content = "ping")),
        )
        given(cachingChat.complete(request, "monitoring-summary", null, "unknown"))
            .willReturn(CachedChatResult(response(), CacheStatus.MISS))

        mockMvc.perform(
            post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Task-Type", "monitoring-summary")
                .content("""{"model":"claude-sonnet-5","messages":[{"role":"user","content":"ping"}]}"""),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("X-Gateway-Cache", "miss"))
            .andExpect(jsonPath("$.object").value("chat.completion"))
            .andExpect(jsonPath("$.choices[0].message.content").value("pong"))
            .andExpect(jsonPath("$.choices[0].finish_reason").value("end_turn"))
            .andExpect(jsonPath("$.usage.prompt_tokens").value(10))
            .andExpect(jsonPath("$.usage.total_tokens").value(15))
    }

    @Test
    fun `캐시 적중은 X-Gateway-Cache 헤더로 드러난다 - no-cache 헤더는 그대로 전달`() {
        val request = ChatCompletionRequest(messages = listOf(ChatMessage(role = "user", content = "ping")))
        given(cachingChat.complete(request, null, "no-cache", "unknown"))
            .willReturn(CachedChatResult(response(), CacheStatus.BYPASS))

        mockMvc.perform(
            post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Cache-Control", "no-cache")
                .content("""{"messages":[{"role":"user","content":"ping"}]}"""),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("X-Gateway-Cache", "bypass"))
    }

    @Test
    fun `폴백 발생은 X-Gateway-Fallback 헤더로 드러난다`() {
        val request = ChatCompletionRequest(messages = listOf(ChatMessage(role = "user", content = "ping")))
        given(cachingChat.complete(request, null, null, "unknown"))
            .willReturn(CachedChatResult(response(), CacheStatus.MISS, fallbackTarget = "openai"))

        mockMvc.perform(
            post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"messages":[{"role":"user","content":"ping"}]}"""),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("X-Gateway-Fallback", "openai"))
    }

    @Test
    fun `폴백이 없으면 X-Gateway-Fallback 헤더도 없다`() {
        val request = ChatCompletionRequest(messages = listOf(ChatMessage(role = "user", content = "ping")))
        given(cachingChat.complete(request, null, null, "unknown"))
            .willReturn(CachedChatResult(response(), CacheStatus.MISS))

        mockMvc.perform(
            post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"messages":[{"role":"user","content":"ping"}]}"""),
        )
            .andExpect(status().isOk)
            .andExpect(header().doesNotExist("X-Gateway-Fallback"))
    }

    @Test
    fun `stream=true 요청은 400 - OpenAI 오류 계약`() {
        mockMvc.perform(
            post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"stream":true,"messages":[{"role":"user","content":"ping"}]}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.param").value("stream"))
    }

    @Test
    fun `빈 messages 는 400`() {
        mockMvc.perform(
            post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"messages":[]}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.param").value("messages"))
    }

    @Test
    fun `임베딩 input 타입 오류는 400 - param 지목`() {
        mockMvc.perform(
            post("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"input":123}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.param").value("input"))
    }

    @Test
    fun `임베딩 input 은 단일 문자열도 수용 - OpenAI 계약`() {
        val request = EmbeddingsRequest(model = null, input = "유사 인시던트 검색")
        given(embeddingRelay.relay(request)).willReturn(
            EmbeddingsResponse(
                data = listOf(EmbeddingData(index = 0, embedding = listOf(0.1f, 0.2f))),
                model = "text-embedding-3-small",
                usage = EmbeddingUsage(promptTokens = 4, totalTokens = 4),
            ),
        )

        mockMvc.perform(
            post("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"input":"유사 인시던트 검색"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.object").value("list"))
            .andExpect(jsonPath("$.data[0].embedding[0]").value(0.1))
            .andExpect(jsonPath("$.usage.prompt_tokens").value(4))
    }
}
