package stillframe42.llmgateway.relay

import com.anthropic.models.messages.ToolChoice
import com.anthropic.models.messages.ToolChoiceAny
import com.anthropic.models.messages.ToolChoiceNone
import com.anthropic.models.messages.ToolChoiceTool
import java.time.Instant
import java.util.UUID
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.stereotype.Service
import stillframe42.llmgateway.api.ChatChoice
import stillframe42.llmgateway.api.ChatCompletionRequest
import stillframe42.llmgateway.api.ChatCompletionResponse
import stillframe42.llmgateway.api.ChatMessage
import stillframe42.llmgateway.api.FunctionCallDto
import stillframe42.llmgateway.api.FunctionSpec
import stillframe42.llmgateway.api.TokenUsage
import stillframe42.llmgateway.api.ToolCallDto
import stillframe42.llmgateway.routing.ModelRouter
import stillframe42.llmgateway.routing.Provider
import stillframe42.llmgateway.routing.Route
import tools.jackson.databind.JsonNode

/**
 * OpenAI 형식 요청을 라우팅 결정(태스크별 프로바이더·모델)에 따라 중계한다.
 * 도구는 passthrough — 정의만 전달하고 tool_calls 를 실행 없이 반환
 * (Spring AI 2.0 ChatModel 은 자체 실행 루프가 없음 — DAY 31 바이트코드 실측).
 */
@Service
class ChatRelayService(
    private val modelRouter: ModelRouter,
    private val chatModels: Map<Provider, ChatModel>,
    private val gatewayMetrics: GatewayMetrics,
) {

    fun relay(request: ChatCompletionRequest, taskType: String?): ChatCompletionResponse =
        relay(request, modelRouter.resolve(taskType, request.model))

    // 라우팅 해석은 호출자(캐시 계층) 몫 — 모델별 캐시 키·필터와 중계가 같은 Route 를 공유한다 (Phase 3)
    fun relay(request: ChatCompletionRequest, route: Route): ChatCompletionResponse {
        val chatModel = requireNotNull(chatModels[route.provider]) { "미구성 프로바이더: ${route.provider}" }
        val prompt = Prompt(toSpringMessages(request.messages), toOptions(route, request))
        val response = chatModel.call(prompt)
        val generation = checkNotNull(response.result) { "프로바이더 응답에 생성 결과가 없습니다" }
        val usage = response.metadata.usage
        val actualModel = response.metadata.model.takeIf { it.isNotBlank() } ?: route.model
        gatewayMetrics.record(route.taskType, route.provider, actualModel)

        val toolCalls = generation.output.toolCalls.orEmpty().map {
            ToolCallDto(id = it.id, function = FunctionCallDto(name = it.name, arguments = it.arguments))
        }
        return ChatCompletionResponse(
            id = response.metadata.id.takeIf { it.isNotBlank() } ?: "chatcmpl-${UUID.randomUUID()}",
            created = Instant.now().epochSecond,
            model = actualModel,
            choices = listOf(
                ChatChoice(
                    index = 0,
                    message = ChatMessage(
                        role = "assistant",
                        content = generation.output.text.orEmpty(),
                        toolCalls = toolCalls.ifEmpty { null },
                    ),
                    // OpenAI 표준값으로 정규화 (Phase 5) — 폴백으로 프로바이더가 바뀌어도 클라이언트는 단일 계약만 본다
                    finishReason = standardFinishReason(generation.metadata.finishReason),
                ),
            ),
            usage = TokenUsage(
                promptTokens = usage.promptTokens,
                completionTokens = usage.completionTokens,
                totalTokens = usage.totalTokens,
            ),
        )
    }

    // 제약 (DAY 30 실측): AnthropicChatModel 은 프롬프트 옵션이 AnthropicChatOptions 일 때만 사용하고
    // 포터블 ChatOptions 는 빈 옵션으로 대체한다 (병합 없음) — 옵션은 반드시 프로바이더 타입으로 만든다
    private fun toOptions(route: Route, request: ChatCompletionRequest): ChatOptions {
        val maxTokens = request.maxTokens ?: route.maxTokens
        val callbacks = request.tools.orEmpty().map { PassthroughToolCallback(it.function) }
        return when (route.provider) {
            Provider.ANTHROPIC -> {
                val b = AnthropicChatOptions.builder().model(route.model)
                maxTokens?.let { b.maxTokens(it) }
                request.temperature?.let { b.temperature(it) }
                if (callbacks.isNotEmpty()) {
                    b.toolCallbacks(callbacks)
                    anthropicToolChoice(request.toolChoice)?.let { b.toolChoice(it) }
                }
                b.build()
            }
            Provider.OPENAI -> {
                val b = OpenAiChatOptions.builder().model(route.model)
                // gpt-5.6 계열은 max_tokens 를 400 으로 하드 거부 — 신형 파라미터만 보낸다 (DAY 33 실측)
                maxTokens?.let { b.maxCompletionTokens(it) }
                request.temperature?.let { b.temperature(it) }
                if (callbacks.isNotEmpty()) {
                    b.toolCallbacks(callbacks)
                    // OpenAI 는 tool_choice 를 원문 형태 그대로 수용
                    request.toolChoice?.let { b.toolChoice(it) }
                }
                b.build()
            }
        }
    }

    private fun JsonNode.stringOrNull(): String? = if (isString) stringValue() else null

    // OpenAI tool_choice → Anthropic SDK ToolChoice 번역 ("auto" 는 기본 동작이라 미지정)
    private fun anthropicToolChoice(node: JsonNode?): ToolChoice? {
        if (node == null) return null
        val text = node.stringOrNull()
        if (text != null) {
            return when (text) {
                "required" -> ToolChoice.ofAny(ToolChoiceAny.builder().build())
                "none" -> ToolChoice.ofNone(ToolChoiceNone.builder().build())
                else -> null
            }
        }
        val forced = node.path("function").path("name").stringOrNull() ?: return null
        return ToolChoice.ofTool(ToolChoiceTool.builder().name(forced).build())
    }

    /** 정의 전달 전용 — 게이트웨이는 도구를 실행하지 않는다 (실행 주체는 클라이언트, ADR-0005 정합) */
    private class PassthroughToolCallback(private val function: FunctionSpec) : ToolCallback {
        override fun getToolDefinition(): ToolDefinition = ToolDefinition.builder()
            .name(function.name)
            .description(function.description ?: function.name)
            .inputSchema(function.parameters?.toString() ?: """{"type":"object","properties":{}}""")
            .build()

        override fun call(input: String): String =
            throw UnsupportedOperationException("게이트웨이는 도구를 실행하지 않는다: ${function.name}")
    }

    companion object {
        /** 프로바이더 원문 finish_reason → OpenAI 표준값 (미지 값은 소문자 원문 통과 — 정보 소실 방지) */
        internal fun standardFinishReason(raw: String?): String? = when (val reason = raw?.lowercase()) {
            null -> null
            "end_turn", "stop_sequence" -> "stop"
            "max_tokens" -> "length"
            "tool_use" -> "tool_calls"
            else -> reason
        }

        internal fun toSpringMessages(messages: List<ChatMessage>): List<Message> = messages.map { msg ->
            when (msg.role) {
                "system" -> SystemMessage(msg.contentText())
                "user" -> UserMessage(msg.contentText())
                "assistant" ->
                    if (msg.toolCalls.isNullOrEmpty()) {
                        AssistantMessage(msg.contentText())
                    } else {
                        val b = AssistantMessage.builder()
                        b.content(msg.contentText())
                        b.toolCalls(
                            msg.toolCalls.map {
                                AssistantMessage.ToolCall(it.id, it.type, it.function.name, it.function.arguments)
                            },
                        )
                        b.build()
                    }
                "tool" -> {
                    val id = msg.toolCallId
                        ?: throw IllegalArgumentException("tool 메시지에는 tool_call_id 가 필요합니다")
                    val b = ToolResponseMessage.builder()
                    b.responses(listOf(ToolResponseMessage.ToolResponse(id, "", msg.contentText())))
                    b.build()
                }
                else -> throw IllegalArgumentException("지원하지 않는 role: ${msg.role}")
            }
        }
    }
}
