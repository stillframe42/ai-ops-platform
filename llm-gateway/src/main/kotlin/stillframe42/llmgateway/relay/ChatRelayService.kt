package stillframe42.llmgateway.relay

import java.time.Instant
import java.util.UUID
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.stereotype.Service
import stillframe42.llmgateway.api.ChatChoice
import stillframe42.llmgateway.api.ChatCompletionRequest
import stillframe42.llmgateway.api.ChatCompletionResponse
import stillframe42.llmgateway.api.ChatMessage
import stillframe42.llmgateway.api.TokenUsage

/**
 * OpenAI 형식 요청을 ChatModel(현재 Anthropic — yml 선택)로 중계한다.
 * Phase 2 라우팅은 이 주입 지점을 "태스크별 ChatModel 선택"으로 대체한다.
 */
@Service
class ChatRelayService(
    private val chatModel: ChatModel,
) {

    fun relay(request: ChatCompletionRequest): ChatCompletionResponse {
        val prompt = Prompt(toSpringMessages(request.messages), toOptions(request))
        val response = chatModel.call(prompt)
        val generation = checkNotNull(response.result) { "프로바이더 응답에 생성 결과가 없습니다" }
        val usage = response.metadata.usage
        return ChatCompletionResponse(
            id = response.metadata.id.takeIf { it.isNotBlank() } ?: "chatcmpl-${UUID.randomUUID()}",
            created = Instant.now().epochSecond,
            model = response.metadata.model.takeIf { it.isNotBlank() } ?: request.model.orEmpty(),
            choices = listOf(
                ChatChoice(
                    index = 0,
                    message = ChatMessage(role = "assistant", content = generation.output.text.orEmpty()),
                    // 프로바이더 원문 그대로 (Anthropic: end_turn) — OpenAI 표준값 매핑은 폴백(프로바이더 교차)과 함께
                    finishReason = generation.metadata.finishReason?.lowercase(),
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
    // 포터블 ChatOptions 는 빈 옵션(라이브러리 기본 모델)으로 대체한다 — 병합 없음(교체 의미론).
    // 따라서 프로바이더 옵션 타입으로 만들고, 부분 지정 시 나머지는 모델 기본 옵션에서 직접 채운다.
    // Phase 2 라우팅(멀티 프로바이더)에서는 프로바이더별 옵션 팩토리로 확장한다
    private fun toOptions(request: ChatCompletionRequest): AnthropicChatOptions? {
        if (request.model == null && request.maxTokens == null && request.temperature == null) return null
        val defaults = chatModel.options
        return AnthropicChatOptions.builder()
            .model(request.model ?: defaults.model)
            .maxTokens(request.maxTokens ?: defaults.maxTokens)
            .temperature(request.temperature ?: defaults.temperature)
            .build()
    }

    companion object {
        internal fun toSpringMessages(messages: List<ChatMessage>): List<Message> = messages.map {
            when (it.role) {
                "system" -> SystemMessage(it.content)
                "user" -> UserMessage(it.content)
                "assistant" -> AssistantMessage(it.content)
                else -> throw IllegalArgumentException("지원하지 않는 role: ${it.role}")
            }
        }
    }
}
