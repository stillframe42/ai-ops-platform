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
    private val cachingChatService: CachingChatService,
    private val embeddingRelayService: EmbeddingRelayService,
) {

    @PostMapping("/v1/chat/completions")
    fun chatCompletions(
        @RequestBody request: ChatCompletionRequest,
        @RequestHeader("X-Task-Type", required = false) taskType: String?,
        @RequestHeader("X-Cache-Control", required = false) cacheControl: String?,
        @RequestHeader("X-Client-Service", required = false) clientService: String?,
    ): ResponseEntity<ChatCompletionResponse> {
        if (request.stream == true) {
            // Phase 0 결정: 스트리밍 미지원 (현행 클라이언트 사용 0건 실측 — 배제 아닌 유예)
            throw InvalidRequestException("스트리밍은 지원하지 않습니다 (stream=false 로 요청)", param = "stream")
        }
        if (request.messages.isEmpty()) {
            throw InvalidRequestException("messages 는 비어 있을 수 없습니다", param = "messages")
        }

        val result = cachingChatService.complete(request, taskType, cacheControl, clientService ?: "unknown")

        val builder = ResponseEntity.ok()
            // 캐시 판정 노출 — 확인 기준 실측·클라이언트 디버깅용 (OpenAI 계약 밖 부가 헤더라 무해)
            .header("X-Gateway-Cache", result.cacheStatus.name.lowercase())
        if (result.downgraded) {
            // 예산 100% 도달로 저비용 모델 강제 전환 — 응답 model 필드와 함께 확인 수단 (Phase 4)
            builder.header("X-Gateway-Downgrade", "budget-exceeded")
        }
        result.fallbackTarget?.let {
            // 주 프로바이더 장애로 폴백 발생 — 값은 교차 프로바이더명 또는 local (Phase 5)
            builder.header("X-Gateway-Fallback", it)
        }
        return builder.body(result.response)
    }

    @PostMapping("/v1/embeddings")
    fun embeddings(@RequestBody request: EmbeddingsRequest): EmbeddingsResponse {
        if (request.inputTexts().isEmpty()) {
            throw InvalidRequestException("input 은 비어 있을 수 없습니다", param = "input")
        }
        return embeddingRelayService.relay(request)
    }
}
