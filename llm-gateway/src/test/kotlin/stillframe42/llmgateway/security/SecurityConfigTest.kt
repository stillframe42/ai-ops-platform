package stillframe42.llmgateway.security

import kotlin.test.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import stillframe42.llmgateway.api.ChatChoice
import stillframe42.llmgateway.api.ChatCompletionRequest
import stillframe42.llmgateway.api.ChatCompletionResponse
import stillframe42.llmgateway.api.ChatMessage
import stillframe42.llmgateway.api.GatewayController
import stillframe42.llmgateway.api.TokenUsage
import stillframe42.llmgateway.cache.CacheStatus
import stillframe42.llmgateway.cache.CachedChatResult
import stillframe42.llmgateway.cache.CachingChatService
import stillframe42.llmgateway.relay.EmbeddingRelayService

/**
 * 인가 규칙 테스트 (ADR-0016) — `/v1` 하위는 `llm:invoke` 스코프만 통과, 서비스 차원은 토큰 `sub`.
 * control-plane SecurityConfigTest 와 같은 구성: `jwt()` 후처리기 + 실 토큰 경로용 JwtDecoder 스텁.
 */
@WebMvcTest(GatewayController::class)
@Import(SecurityConfig::class, SecurityConfigTest.JwtStub::class)
class SecurityConfigTest(@Autowired private val mvc: MockMvc) {

    @TestConfiguration
    class JwtStub {
        @Bean
        fun jwtDecoder(): JwtDecoder = JwtDecoder { throw BadJwtException("테스트 스텁 — 실 토큰 검증 없음") }
    }

    @MockitoBean
    private lateinit var cachingChatService: CachingChatService

    @MockitoBean
    private lateinit var embeddingRelayService: EmbeddingRelayService

    private val body = """{"messages":[{"role":"user","content":"ping"}]}"""
    private val request = ChatCompletionRequest(messages = listOf(ChatMessage(role = "user", content = "ping")))

    private fun chatResponse() = ChatCompletionResponse(
        id = "chatcmpl-test",
        created = 1_755_400_000,
        model = "claude-sonnet-5",
        choices = listOf(ChatChoice(index = 0, message = ChatMessage("assistant", "pong"), finishReason = "end_turn")),
        usage = TokenUsage(promptTokens = 1, completionTokens = 1, totalTokens = 2),
    )

    @Test
    fun `llm invoke 토큰은 통과하고 서비스 차원은 토큰 sub 이다`() {
        // 스텁의 서비스 인자가 토큰 sub 와 같을 때만 응답이 있다 — 다르면 null 반환으로 500
        given(cachingChatService.complete(request, null, null, "agent-service"))
            .willReturn(CachedChatResult(chatResponse(), CacheStatus.MISS))

        mvc.perform(
            post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(jwt().jwt { it.subject("agent-service") }.authorities(SimpleGrantedAuthority(SecurityConfig.SCOPE_LLM_INVOKE))),
        ).andExpect(status().isOk)
    }

    @Test
    fun `X-Client-Service 헤더는 더 이상 서비스 차원이 아니다`() {
        given(cachingChatService.complete(request, null, null, "control-plane"))
            .willReturn(CachedChatResult(chatResponse(), CacheStatus.MISS))

        mvc.perform(
            post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Client-Service", "forged-service")
                .with(jwt().jwt { it.subject("control-plane") }.authorities(SimpleGrantedAuthority(SecurityConfig.SCOPE_LLM_INVOKE))),
        ).andExpect(status().isOk)
    }

    @Test
    fun `llm invoke 가 없는 토큰은 403 이다`() {
        val opsAdmin = jwt().authorities(SimpleGrantedAuthority("SCOPE_ops:approve"), SimpleGrantedAuthority("SCOPE_ops:read"))

        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(body).with(opsAdmin))
            .andExpect(status().isForbidden)
        mvc.perform(post("/v1/embeddings").contentType(MediaType.APPLICATION_JSON).content("""{"input":"x"}""").with(opsAdmin))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `무토큰·위조 토큰은 401 이다`() {
        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized)
            .andExpect(header().exists("WWW-Authenticate"))
        mvc.perform(
            post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer forged.token.value"),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `actuator probe 는 토큰 없이 통과한다`() {
        // 슬라이스에 actuator 가 없어 404 — 401 이 아니면 permitAll 이 적용된 것
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isNotFound)
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isNotFound)
    }
}
