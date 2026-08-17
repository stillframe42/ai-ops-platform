package stillframe42.llmgateway.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import stillframe42.llmgateway.api.ChatCompletionRequest
import stillframe42.llmgateway.api.ChatMessage

/**
 * 회귀 방지 (DAY 30 실측 버그): AnthropicChatModel 은 프롬프트 옵션이 AnthropicChatOptions 일 때만
 * 사용하고 포터블 ChatOptions 는 빈 옵션(DEFAULT_MODEL=haiku)으로 대체한다 — 병합 없음.
 * 게이트웨이의 요청 옵션은 반드시 프로바이더 옵션 타입으로 전달되어야 한다.
 */
class ChatRelayOptionsTest {

    private class CapturingChatModel(
        private val defaults: ChatOptions = ChatOptions.builder().build(),
    ) : ChatModel {
        lateinit var captured: Prompt

        override fun call(prompt: Prompt): ChatResponse {
            captured = prompt
            return ChatResponse(listOf(Generation(AssistantMessage("pong"))))
        }

        override fun getOptions(): ChatOptions = defaults
    }

    private fun request(model: String? = null, maxTokens: Int? = null) = ChatCompletionRequest(
        model = model,
        messages = listOf(ChatMessage("user", "ping")),
        maxTokens = maxTokens,
    )

    @Test
    fun `요청의 model·max_tokens 는 AnthropicChatOptions 로 프로바이더에 도달한다`() {
        val chatModel = CapturingChatModel()
        ChatRelayService(chatModel).relay(request(model = "claude-sonnet-5", maxTokens = 64))

        val options = assertIs<AnthropicChatOptions>(chatModel.captured.options)
        assertEquals("claude-sonnet-5", options.model)
        assertEquals(64, options.maxTokens)
    }

    @Test
    fun `부분 지정 시 나머지는 프로바이더 기본 옵션에서 채운다 - 교체 의미론 방어`() {
        val defaults = AnthropicChatOptions.builder().model("claude-sonnet-5").maxTokens(2000).build()
        val chatModel = CapturingChatModel(defaults)
        ChatRelayService(chatModel).relay(request(maxTokens = 64))

        val options = assertIs<AnthropicChatOptions>(chatModel.captured.options)
        assertEquals("claude-sonnet-5", options.model)
        assertEquals(64, options.maxTokens)
    }

    @Test
    fun `옵션 무지정 요청은 옵션 없이 전달한다 - 서버 기본값 경로`() {
        val chatModel = CapturingChatModel()
        ChatRelayService(chatModel).relay(request())

        assertNull(chatModel.captured.options)
    }
}
