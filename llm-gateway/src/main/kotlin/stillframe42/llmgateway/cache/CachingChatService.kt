package stillframe42.llmgateway.cache

import org.springframework.stereotype.Service
import stillframe42.llmgateway.api.ChatCompletionRequest
import stillframe42.llmgateway.api.ChatCompletionResponse
import stillframe42.llmgateway.budget.BudgetDecision
import stillframe42.llmgateway.budget.BudgetGuard
import stillframe42.llmgateway.cost.CostRecorder
import stillframe42.llmgateway.relay.ChatRelayService
import stillframe42.llmgateway.relay.GatewayMetrics
import stillframe42.llmgateway.routing.ModelRouter

/**
 * 2단계 시맨틱 캐싱 오케스트레이션 (weekly-plan Phase 3) — 정확 일치 → 의미 유사도 → 중계.
 * 저장소 상호작용·직렬화는 ExactResponseCache/SemanticResponseCache 소관 — 여기는 순서·판정·메트릭만.
 * 제외 규칙: tool calling 요청(정의·이력 모두 — 상태 의존)과 X-Cache-Control: no-cache 는 우회.
 * Phase 4: 라우팅 해석 직후 예산 판정(한도 초과 = 저비용 다운그레이드), 응답 후 비용 계상·정산.
 */
@Service
class CachingChatService(
    private val router: ModelRouter,
    private val relay: ChatRelayService,
    private val exactCache: ExactResponseCache,
    private val semanticCache: SemanticResponseCache,
    private val budget: BudgetGuard,
    private val costRecorder: CostRecorder,
    private val metrics: GatewayMetrics,
) {

    fun complete(
        request: ChatCompletionRequest,
        taskType: String?,
        cacheControl: String?,
        service: String,
    ): CachedChatResult {
        // 다운그레이드된 라우트가 캐시 키·모델 필터에도 그대로 쓰인다 — 원 모델 캐시와 격리 (DAY 31 연결 메모)
        val decision = budget.enforce(router.resolve(taskType, request.model), service)
        val route = decision.route
        if (!cacheable(request, cacheControl)) {
            return finish(relay.relay(request, route), CacheStatus.BYPASS, service, decision)
        }

        val key = exactCache.keyOf(route, request)
        exactCache.find(key)?.let { return hit(it, CacheStatus.EXACT_HIT, service, decision) }

        semanticCache.findSimilar(request, route)?.let { cached ->
            // 의미 캐시 히트를 정확 캐시로 승격 — 같은 정확 요청의 다음 조회는 임베딩 없이 적중
            exactCache.save(key, cached)
            return hit(cached, CacheStatus.SEMANTIC_HIT, service, decision)
        }

        val response = relay.relay(request, route)
        exactCache.save(key, response)
        semanticCache.save(request, route, decision.route.taskType, response)
        return finish(response, CacheStatus.MISS, service, decision)
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
        metrics.cacheSaved(cached.model, cached.usage)
        return finish(cached, status, service, decision)
    }

    private fun finish(
        response: ChatCompletionResponse,
        status: CacheStatus,
        service: String,
        decision: BudgetDecision,
    ): CachedChatResult {
        metrics.cache(status, decision.route.taskType)
        val cost = costRecorder.record(service, decision.route, response, status)
        budget.settle(service, cost)
        return CachedChatResult(response, status, decision.downgraded)
    }
}
