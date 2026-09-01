package stillframe42.llmgateway.masking

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import stillframe42.llmgateway.api.ChatCompletionRequest
import stillframe42.llmgateway.api.ChatMessage
import stillframe42.llmgateway.api.EmbeddingsRequest
import stillframe42.llmgateway.relay.GatewayMetrics

/**
 * LLM 방향 입력 마스킹 (가드레일 체인과 같은 강제 지점 — 에이전트 코드와 무관하게 게이트웨이에서 수행).
 * 대상 role 은 가드레일과 같은 user·tool — system·assistant 는 인증된 클라이언트 코드의 소유다.
 * 캐시 조회보다 앞에서 호출한다 — 캐시 키·저장 응답·임베딩(RAG 영속)이 마스킹된 텍스트 기준이 된다.
 */
@Service
class InputMaskingService(
    private val maskingProperties: MaskingProperties,
    private val gatewayMetrics: GatewayMetrics,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun mask(request: ChatCompletionRequest): ChatCompletionRequest {
        if (!maskingProperties.enabled) return request
        var changed = false
        val messages = request.messages.map { message ->
            if (message.role !in MASKED_ROLES) return@map message
            val masked = maskContent(message)
            if (masked !== message) changed = true
            masked
        }
        return if (changed) request.copy(messages = messages) else request
    }

    fun mask(request: EmbeddingsRequest): EmbeddingsRequest {
        if (!maskingProperties.enabled || !maskingProperties.includeEmbeddings) return request
        val results = request.inputTexts().map { maskAndRecord(it) }
        if (results.all { it.hits.isEmpty() }) return request
        return request.copy(input = results.map { it.text })
    }

    private fun maskContent(message: ChatMessage): ChatMessage = when (val content = message.content) {
        is String -> {
            val result = maskAndRecord(content)
            if (result.hits.isEmpty()) message else message.copy(content = result.text)
        }
        // 블록 배열([{type:text, text:...}]) — text 값만 치환하고 블록 구조는 보존
        is List<*> -> {
            var changed = false
            val blocks = content.map { block ->
                val text = (block as? Map<*, *>)?.get("text") as? String
                if (text == null) {
                    block
                } else {
                    val result = maskAndRecord(text)
                    if (result.hits.isEmpty()) {
                        block
                    } else {
                        changed = true
                        block.entries.associate { (k, v) -> k to if (k == "text") result.text else v }
                    }
                }
            }
            if (changed) message.copy(content = blocks) else message
        }
        else -> message
    }

    private fun maskAndRecord(text: String): MaskingResult {
        val result = SensitiveDataMasker.mask(text)
        result.hits.forEach { (pattern, count) ->
            gatewayMetrics.masking(pattern, count)
            logger.warn("입력 마스킹 — pattern={} count={}", pattern, count)
        }
        return result
    }

    companion object {
        val MASKED_ROLES = setOf("user", "tool")
    }
}
