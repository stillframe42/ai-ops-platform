package stillframe42.llmgateway.cache

import org.springframework.stereotype.Service
import stillframe42.llmgateway.api.ChatCompletionRequest
import stillframe42.llmgateway.api.ChatCompletionResponse
import stillframe42.llmgateway.budget.BudgetDecision
import stillframe42.llmgateway.budget.BudgetGuard
import stillframe42.llmgateway.cost.CostRecorder
import stillframe42.llmgateway.fallback.FallbackStatus
import stillframe42.llmgateway.fallback.RelayOutcome
import stillframe42.llmgateway.fallback.FallbackChatRelayService
import stillframe42.llmgateway.relay.GatewayMetrics
import stillframe42.llmgateway.routing.ModelRouter
import stillframe42.llmgateway.routing.Route

/**
 * 2단계 시맨틱 캐싱 오케스트레이션 (weekly-plan Phase 3) — 정확 일치 → 의미 유사도 → 중계.
 * 저장소 상호작용·직렬화는 ExactResponseCache/SemanticResponseCache 소관 — 여기는 순서·판정·메트릭만.
 * 제외 규칙: tool calling 요청(정의·이력 모두 — 상태 의존)과 X-Cache-Control: no-cache 는 우회.
 * Phase 4: 라우팅 해석 직후 예산 판정(한도 초과 = 저비용 다운그레이드), 응답 후 비용 기록·정산.
 * Phase 5: 중계는 폴백 체인 경유 — 폴백 응답은 캐시에 저장하지 않고(장애 중 생성물이 원 모델
 * 키·의미 캐시 필터 아래 들어가 정상 캐시를 오염), 비용은 실사용 라우트로 기록한다.
 */
@Service
class CachingChatService(
    private val modelRouter: ModelRouter,
    private val fallbackChatRelayService: FallbackChatRelayService,
    private val exactResponseCache: ExactResponseCache,
    private val semanticResponseCache: SemanticResponseCache,
    private val budgetGuard: BudgetGuard,
    private val costRecorder: CostRecorder,
    private val gatewayMetrics: GatewayMetrics,
) {

    fun complete(
        request: ChatCompletionRequest,
        taskType: String?,
        cacheControl: String?,
        service: String,
    ): CachedChatResult {
        // 다운그레이드된 라우트가 캐시 키·모델 필터에도 그대로 쓰인다 — 원 모델 캐시와 격리 (DAY 31 연결 메모)
        val decision = budgetGuard.enforce(modelRouter.resolve(taskType, request.model), service)
        val route = decision.route
        if (!cacheable(request, cacheControl)) {
            return finish(fallbackChatRelayService.relay(request, route), CacheStatus.BYPASS, service, decision)
        }

        val key = exactResponseCache.keyOf(route, request)
        exactResponseCache.find(key)?.let { return hit(it, CacheStatus.EXACT_HIT, service, decision) }

        semanticResponseCache.findSimilar(request, route)?.let { cached ->
            // 의미 캐시 히트를 정확 캐시로 승격 — 같은 정확 요청의 다음 조회는 임베딩 없이 적중
            exactResponseCache.save(key, cached)
            return hit(cached, CacheStatus.SEMANTIC_HIT, service, decision)
        }

        val outcome = fallbackChatRelayService.relay(request, route)
        if (outcome.fallback == FallbackStatus.NONE) {
            exactResponseCache.save(key, outcome.response)
            semanticResponseCache.save(request, route, route.taskType, outcome.response)
        }
        return finish(outcome, CacheStatus.MISS, service, decision)
    }

    private fun cacheable(request: ChatCompletionRequest, cacheControl: String?): Boolean {
        if (cacheControl?.equals("no-cache", ignoreCase = true) == true) return false
        if (!request.tools.isNullOrEmpty()) return false
        // tool 이력 포함 대화 — 도구 실행 결과에 의존하는 응답은 재사용 불가
        if (request.messages.any { it.role == "tool" || !it.toolCalls.isNullOrEmpty() }) return false
        return true
    }

    private fun hit(
        cached: ChatCompletionResponse,
        status: CacheStatus,
        service: String,
        decision: BudgetDecision,
    ): CachedChatResult {
        gatewayMetrics.cacheSaved(cached.model, cached.usage)
        return finish(cached, status, service, decision, effectiveRoute = decision.route, fallbackTarget = null)
    }

    private fun finish(outcome: RelayOutcome, status: CacheStatus, service: String, decision: BudgetDecision) =
        finish(
            outcome.response, status, service, decision,
            effectiveRoute = outcome.route,
            fallbackTarget = when (outcome.fallback) {
                FallbackStatus.NONE -> null
                FallbackStatus.PROVIDER -> outcome.route.provider.name.lowercase()
                FallbackStatus.LOCAL -> "local"
            },
        )

    private fun finish(
        response: ChatCompletionResponse,
        status: CacheStatus,
        service: String,
        decision: BudgetDecision,
        effectiveRoute: Route,
        fallbackTarget: String?,
    ): CachedChatResult {
        gatewayMetrics.cache(status, decision.route.taskType)
        val cost = costRecorder.record(service, effectiveRoute, response, status)
        budgetGuard.settle(service, cost)
        return CachedChatResult(response, status, decision.downgraded, fallbackTarget)
    }
}
