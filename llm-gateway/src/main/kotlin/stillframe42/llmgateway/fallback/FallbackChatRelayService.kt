package stillframe42.llmgateway.fallback

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import stillframe42.llmgateway.api.ChatChoice
import stillframe42.llmgateway.api.ChatCompletionRequest
import stillframe42.llmgateway.api.ChatCompletionResponse
import stillframe42.llmgateway.api.ChatMessage
import stillframe42.llmgateway.api.TokenUsage
import stillframe42.llmgateway.relay.ChatRelayService
import stillframe42.llmgateway.relay.GatewayMetrics
import stillframe42.llmgateway.routing.Provider
import stillframe42.llmgateway.routing.Route

/**
 * 프로바이더 폴백 체인 (weekly-plan Phase 5 ②) — 주 중계 → 교차 프로바이더 재중계 → 로컬 폴백 응답.
 * 서킷은 프로바이더 단위 — 오픈이면 주 중계를 건너뛰어 장애 프로바이더로의 대기 시간을 없앤다.
 * 폴백 트리거는 프로바이더 호출의 모든 예외 (5xx·타임아웃·무효 키) — 게이트웨이 검증을 통과한
 * 요청의 실패는 프로바이더 측 사정. 단 IllegalArgumentException 은 클라이언트 잘못이라
 * 어느 프로바이더로도 같은 실패 → 폴백 없이 전파 (서킷 실패율에서도 ignoreExceptions 로 제외)
 */
class FallbackChatRelayService(
    private val chatRelayService: ChatRelayService,
    private val fallbackProperties: FallbackProperties,
    private val circuitBreakerRegistry: CircuitBreakerRegistry,
    private val gatewayMetrics: GatewayMetrics,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun relay(request: ChatCompletionRequest, route: Route): RelayOutcome {
        val breaker = circuitBreakerRegistry.circuitBreaker("provider-${route.provider.name.lowercase()}")
        val primaryFailure = runCatching {
            return RelayOutcome(
                breaker.executeSupplier { chatRelayService.relay(request, route) },
                route,
                FallbackStatus.NONE,
            )
        }.exceptionOrNull()!!
        if (primaryFailure is IllegalArgumentException) throw primaryFailure

        val fallbackProvider = Provider.valueOf(fallbackProperties.provider.uppercase())
        if (route.provider != fallbackProvider) {
            val fallbackRoute = Route(
                taskType = route.taskType,
                provider = fallbackProvider,
                model = fallbackProperties.model,
                maxTokens = request.maxTokens ?: route.maxTokens ?: fallbackProperties.maxTokens,
            )
            val fallbackResult = runCatching { chatRelayService.relay(request, fallbackRoute) }
            fallbackResult.onSuccess {
                logger.warn(
                    "주 프로바이더({}) 장애 — {} 로 폴백 완료: {}",
                    route.provider, fallbackProvider, primaryFailure.message,
                )
                gatewayMetrics.fallback(route.provider, target = fallbackProvider.name.lowercase())
                return RelayOutcome(it, fallbackRoute, FallbackStatus.PROVIDER)
            }
            // 폴백 실패 원인을 따로 남긴다 — 아래 최종 로그는 주 실패만 담아 재중계 실패가 무로그가 된다 (DAY 33 실측)
            logger.error("폴백 프로바이더({}) 재중계 실패: {}", fallbackProvider, fallbackResult.exceptionOrNull()?.message)
        }
        logger.error("전 프로바이더 중계 실패 — 로컬 폴백 응답 반환: {}", primaryFailure.message)
        gatewayMetrics.fallback(route.provider, target = "local")
        return RelayOutcome(localResponse(), route, FallbackStatus.LOCAL)
    }

    private fun localResponse() = ChatCompletionResponse(
        id = "chatcmpl-local-${UUID.randomUUID()}",
        created = Instant.now().epochSecond,
        model = LOCAL_FALLBACK_MODEL,
        choices = listOf(
            ChatChoice(index = 0, message = ChatMessage("assistant", LOCAL_FALLBACK_MESSAGE), finishReason = "stop"),
        ),
        usage = TokenUsage(promptTokens = 0, completionTokens = 0, totalTokens = 0),
    )

    companion object {
        const val LOCAL_FALLBACK_MODEL = "gateway-local-fallback"

        // 파이프라인 비정지 원칙 (예산 다운그레이드와 같은 취지) — 오류 대신 성격을 밝힌 응답으로 완주시킨다
        const val LOCAL_FALLBACK_MESSAGE =
            "LLM 프로바이더 전체 장애로 분석 응답을 생성하지 못했습니다. " +
                "이 응답은 게이트웨이의 로컬 폴백이며 분석 결과가 아닙니다 — 잠시 후 다시 시도하세요."
    }
}
