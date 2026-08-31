package stillframe42.llmgateway.api

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import stillframe42.llmgateway.cache.CachingChatService
import stillframe42.llmgateway.guardrail.GuardrailBlockedException
import stillframe42.llmgateway.guardrail.GuardrailStage
import stillframe42.llmgateway.guardrail.GuardrailVerdict
import stillframe42.llmgateway.guardrail.InputGuardrailChain
import stillframe42.llmgateway.relay.EmbeddingRelayService
import stillframe42.llmgateway.security.ClientIdentity

@RestController
class GatewayController(
    private val cachingChatService: CachingChatService,
    private val embeddingRelayService: EmbeddingRelayService,
    private val inputGuardrailChain: InputGuardrailChain,
) {

    @PostMapping("/v1/chat/completions")
    fun chatCompletions(
        @RequestBody request: ChatCompletionRequest,
        @RequestHeader(GatewayHeaders.TASK_TYPE, required = false) taskType: String?,
        @RequestHeader(GatewayHeaders.CACHE_CONTROL, required = false) cacheControl: String?,
    ): ResponseEntity<ChatCompletionResponse> {
        if (request.stream == true) {
            // 스트리밍 미지원 (현행 클라이언트 사용 0건 실측 — 배제 아닌 유예)
            throw InvalidRequestException("스트리밍은 지원하지 않습니다 (stream=false 로 요청)", param = "stream")
        }
        if (request.messages.isEmpty()) {
            throw InvalidRequestException("messages 는 비어 있을 수 없습니다", param = "messages")
        }

        // 입력 가드레일은 캐시 조회보다 앞 — 판정이 캐시 정책(비클린 = 저장 금지)을 결정한다
        val guardrail = inputGuardrailChain.evaluate(request)
        if (guardrail.verdict == GuardrailVerdict.BLOCKED) throw GuardrailBlockedException(guardrail)
        // 비클린 요청의 응답은 캐시에 남기지 않는다 — 주입 영향을 받은 응답이 유사 질의에 재사용되는 오염 경로 차단
        val effectiveCacheControl = if (guardrail.isClean) cacheControl else "no-cache"

        // 서비스 차원은 검증된 토큰의 client_id — 헤더 자기 신고(X-Client-Service)는 위조 가능해 제거 (ADR-0016)
        val result = cachingChatService.complete(request, taskType, effectiveCacheControl, ClientIdentity.current())

        val builder = ResponseEntity.ok()
            // 캐시 판정 노출 — 확인 기준 실측·클라이언트 디버깅용 (OpenAI 계약 밖 부가 헤더라 무해)
            .header(GatewayHeaders.CACHE, result.cacheStatus.name.lowercase())
            // 가드레일 판정 — 캐시 헤더와 같은 항상-존재 계약 (clean / suspect / flagged), 단계는 비클린일 때만
            .header(GatewayHeaders.GUARDRAIL, guardrail.verdict.name.lowercase())
        if (guardrail.stage != GuardrailStage.NONE) {
            builder.header(GatewayHeaders.GUARDRAIL_STAGE, guardrail.stage.name.lowercase())
        }
        if (result.downgraded) {
            // 예산 100% 도달로 저비용 모델 강제 전환 — 응답 model 필드와 함께 확인 수단
            builder.header(GatewayHeaders.DOWNGRADE, "budget-exceeded")
        }
        result.fallbackTarget?.let {
            // 주 프로바이더 장애로 폴백 발생 — 값은 교차 프로바이더명 또는 local
            builder.header(GatewayHeaders.FALLBACK, it)
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
