package stillframe42.llmgateway.api

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import stillframe42.llmgateway.cache.CachingChatService
import stillframe42.llmgateway.relay.EmbeddingRelayService

@RestController
class GatewayController(
    private val cachingChat: CachingChatService,
    private val embeddingRelay: EmbeddingRelayService,
) {

    @PostMapping("/v1/chat/completions")
    fun chatCompletions(
        @RequestBody request: ChatCompletionRequest,
        @RequestHeader("X-Task-Type", required = false) taskType: String?,
        @RequestHeader("X-Cache-Control", required = false) cacheControl: String?,
    ): ResponseEntity<ChatCompletionResponse> {
        if (request.stream == true) {
            // Phase 0 결정: 스트리밍 미지원 (현행 클라이언트 사용 0건 실측 — 배제 아닌 유예)
            throw InvalidRequestException("스트리밍은 지원하지 않습니다 (stream=false 로 요청)", param = "stream")
        }
        if (request.messages.isEmpty()) {
            throw InvalidRequestException("messages 는 비어 있을 수 없습니다", param = "messages")
        }
        val result = cachingChat.complete(request, taskType, cacheControl)
        return ResponseEntity.ok()
            // 캐시 판정 노출 — 확인 기준 실측·클라이언트 디버깅용 (OpenAI 계약 밖 부가 헤더라 무해)
            .header("X-Gateway-Cache", result.cacheStatus.name.lowercase())
            .body(result.response)
    }

    @PostMapping("/v1/embeddings")
    fun embeddings(@RequestBody request: EmbeddingsRequest): EmbeddingsResponse {
        if (request.inputTexts().isEmpty()) {
            throw InvalidRequestException("input 은 비어 있을 수 없습니다", param = "input")
        }
        return embeddingRelay.relay(request)
    }
}
