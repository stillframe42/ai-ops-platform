package stillframe42.llmgateway.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.JsonNode
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming

/**
 * OpenAI 호환 표면 계약 (ADR-0015) — 클라이언트는 base-url 전환만으로 접속한다.
 * 스트리밍 미지원 (Phase 0 결정 — 유예): stream=true 요청은 400.
 * 도구는 passthrough — 게이트웨이는 정의를 중계하고 tool_calls 를 반환할 뿐, 실행 주체는 클라이언트.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ChatCompletionRequest(
    val model: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    val stream: Boolean? = null,
    val tools: List<ToolSpec>? = null,
    // OpenAI 계약상 문자열("auto"/"none"/"required") 또는 강제 지정 객체 — JsonNode 로 받아 프로바이더별 번역
    val toolChoice: JsonNode? = null,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChatMessage(
    val role: String,
    // OpenAI 계약상 문자열 또는 블록 배열([{type:text, text:...}]) — langchain 1.x 는 배열로 보낸다 (DAY 31 실측 400)
    val content: Any? = null,
    val toolCalls: List<ToolCallDto>? = null,
    val toolCallId: String? = null,
) {
    fun contentText(): String = when (content) {
        null -> ""
        is String -> content
        is List<*> -> content.joinToString("") { block ->
            (block as? Map<*, *>)?.let { it["text"]?.toString() ?: "" } ?: ""
        }
        else -> content.toString()
    }
}

data class ToolSpec(
    val type: String = "function",
    val function: FunctionSpec,
)

data class FunctionSpec(
    val name: String,
    val description: String? = null,
    // JSON Schema — 해석하지 않고 프로바이더에 그대로 전달
    val parameters: JsonNode? = null,
)

data class ToolCallDto(
    val id: String,
    val type: String = "function",
    val function: FunctionCallDto,
)

data class FunctionCallDto(
    val name: String,
    val arguments: String,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ChatCompletionResponse(
    val id: String,
    @JsonProperty("object") val objectType: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<ChatChoice>,
    val usage: TokenUsage,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ChatChoice(
    val index: Int,
    val message: ChatMessage,
    val finishReason: String?,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class EmbeddingsRequest(
    val model: String? = null,
    // OpenAI 계약상 문자열 또는 문자열 배열 — 역직렬화 후 inputTexts() 로 정규화
    val input: Any = emptyList<String>(),
) {
    fun inputTexts(): List<String> = when (input) {
        is String -> listOf(input)
        is List<*> -> input.map { it.toString() }
        else -> throw InvalidRequestException("input 은 문자열 또는 문자열 배열이어야 합니다", param = "input")
    }
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class EmbeddingsResponse(
    @JsonProperty("object") val objectType: String = "list",
    val data: List<EmbeddingData>,
    val model: String,
    val usage: EmbeddingUsage,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class EmbeddingData(
    @JsonProperty("object") val objectType: String = "embedding",
    val index: Int,
    val embedding: List<Float>,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class EmbeddingUsage(
    val promptTokens: Int,
    val totalTokens: Int,
)

/** OpenAI 오류 계약 — 클라이언트 SDK 가 그대로 해석할 수 있는 형태 */
data class OpenAiError(val error: Detail) {
    data class Detail(
        val message: String,
        val type: String,
        val param: String? = null,
        val code: String? = null,
    )

    companion object {
        fun invalidRequest(message: String, param: String? = null) =
            OpenAiError(Detail(message = message, type = "invalid_request_error", param = param))

        fun rateLimited(message: String) =
            OpenAiError(Detail(message = message, type = "rate_limit_error", code = "rate_limit_exceeded"))
    }
}
