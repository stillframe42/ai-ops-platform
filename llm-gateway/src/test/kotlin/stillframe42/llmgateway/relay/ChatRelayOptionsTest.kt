package stillframe42.llmgateway.relay

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.openai.OpenAiChatOptions
import stillframe42.llmgateway.api.ChatCompletionRequest
import stillframe42.llmgateway.api.ChatMessage
import stillframe42.llmgateway.api.FunctionSpec
import stillframe42.llmgateway.api.ToolSpec
import stillframe42.llmgateway.routing.ModelRouter
import stillframe42.llmgateway.routing.Provider
import stillframe42.llmgateway.routing.RoutingProperties

/**
 * 회귀 방지 (DAY 30 실측 버그): 프로바이더 모델은 자기 옵션 타입일 때만 옵션을 사용한다 (교체 의미론).
 * 라우팅 결정은 반드시 프로바이더 옵션 타입으로 프롬프트에 도달해야 한다.
 */
class ChatRelayOptionsTest {

    private class CapturingChatModel(private val reply: AssistantMessage = AssistantMessage("pong")) : ChatModel {
        lateinit var captured: Prompt

        override fun call(prompt: Prompt): ChatResponse {
            captured = prompt
            return ChatResponse(listOf(Generation(reply)))
        }
    }

    private val router = ModelRouter(
        RoutingProperties(
            rules = listOf(
                RoutingProperties.Rule(task = "monitoring-summary", provider = "anthropic", model = "claude-haiku-4-5", maxTokens = 2000),
                RoutingProperties.Rule(task = "code-review-critical", provider = "openai", model = "gpt-5.6-terra"),
            ),
        ),
    )

    private fun relayWith(anthropic: ChatModel, openai: ChatModel = CapturingChatModel()) =
        ChatRelayService(router, mapOf(Provider.ANTHROPIC to anthropic, Provider.OPENAI to openai), GatewayMetrics(SimpleMeterRegistry()))

    private fun request(model: String? = null, tools: List<ToolSpec>? = null) = ChatCompletionRequest(
        model = model,
        messages = listOf(ChatMessage("user", "ping")),
        tools = tools,
    )

    @Test
    fun `태스크 규칙이 AnthropicChatOptions 로 도달한다 - body model 무시`() {
        val anthropic = CapturingChatModel()
        relayWith(anthropic).relay(request(model = "claude-sonnet-5"), taskType = "monitoring-summary")

        val options = assertIs<AnthropicChatOptions>(anthropic.captured.options)
        assertEquals("claude-haiku-4-5", options.model)
        assertEquals(2000, options.maxTokens)
    }

    @Test
    fun `openai 규칙은 OpenAiChatOptions 로 도달한다`() {
        val openai = CapturingChatModel()
        relayWith(CapturingChatModel(), openai).relay(request(), taskType = "code-review-critical")

        val options = assertIs<OpenAiChatOptions>(openai.captured.options)
        assertEquals("gpt-5.6-terra", options.model)
    }

    @Test
    fun `tools 는 정의만 담긴 ToolCallback 으로 전달된다 - passthrough`() {
        val anthropic = CapturingChatModel()
        val tools = listOf(ToolSpec(function = FunctionSpec(name = "query_prometheus", description = "PromQL 조회")))
        relayWith(anthropic).relay(request(tools = tools), taskType = "root-cause-analysis")

        val options = assertIs<ToolCallingChatOptions>(anthropic.captured.options)
        assertEquals(listOf("query_prometheus"), options.toolCallbacks.orEmpty().map { it.toolDefinition.name() })
    }

    @Test
    fun `응답의 tool_calls 가 OpenAI 계약으로 매핑된다`() {
        val reply = AssistantMessage.builder().let { b ->
            b.content("")
            b.toolCalls(listOf(AssistantMessage.ToolCall("tc_1", "function", "query_prometheus", """{"q":"up"}""")))
            b.build()
        }
        val anthropic = CapturingChatModel(reply)
        val response = relayWith(anthropic).relay(request(), taskType = null)

        val message = response.choices.single().message
        assertEquals("query_prometheus", message.toolCalls?.single()?.function?.name)
        assertEquals("""{"q":"up"}""", message.toolCalls?.single()?.function?.arguments)
    }

    @Test
    fun `도구 없는 응답의 tool_calls 는 null - OpenAI 계약 (빈 배열 아님)`() {
        val anthropic = CapturingChatModel()
        val response = relayWith(anthropic).relay(request(), taskType = null)
        assertNull(response.choices.single().message.toolCalls)
    }
}
