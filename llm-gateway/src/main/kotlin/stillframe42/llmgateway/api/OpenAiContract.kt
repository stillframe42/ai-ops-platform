package stillframe42.llmgateway.api

import tools.jackson.databind.annotation.JsonNaming
import tools.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * OpenAI 호환 표면 계약 (ADR-0015) — 클라이언트는 base-url 전환만으로 접속한다.
 * 스트리밍 미지원 (Phase 0 결정 — 유예): stream=true 요청은 400.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ChatCompletionRequest(
    val model: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    val stream: Boolean? = null,
)

data class ChatMessage(
    val role: String,
    val content: String,
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
    }
}
