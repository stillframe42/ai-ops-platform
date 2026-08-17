package stillframe42.llmgateway.api

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import stillframe42.llmgateway.relay.ChatRelayService
import stillframe42.llmgateway.relay.EmbeddingRelayService

@RestController
class GatewayController(
    private val chatRelay: ChatRelayService,
    private val embeddingRelay: EmbeddingRelayService,
) {

    @PostMapping("/v1/chat/completions")
    fun chatCompletions(@RequestBody request: ChatCompletionRequest): ChatCompletionResponse {
        if (request.stream == true) {
            // Phase 0 결정: 스트리밍 미지원 (현행 클라이언트 사용 0건 실측 — 배제 아닌 유예)
            throw InvalidRequestException("스트리밍은 지원하지 않습니다 (stream=false 로 요청)", param = "stream")
        }
        if (request.messages.isEmpty()) {
            throw InvalidRequestException("messages 는 비어 있을 수 없습니다", param = "messages")
        }
        return chatRelay.relay(request)
    }

    @PostMapping("/v1/embeddings")
    fun embeddings(@RequestBody request: EmbeddingsRequest): EmbeddingsResponse {
        if (request.inputTexts().isEmpty()) {
            throw InvalidRequestException("input 은 비어 있을 수 없습니다", param = "input")
        }
        return embeddingRelay.relay(request)
    }
}
