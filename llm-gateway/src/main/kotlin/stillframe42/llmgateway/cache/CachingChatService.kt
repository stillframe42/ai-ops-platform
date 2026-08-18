package stillframe42.llmgateway.cache

import org.springframework.stereotype.Service
import stillframe42.llmgateway.api.ChatCompletionRequest
import stillframe42.llmgateway.api.ChatCompletionResponse
import stillframe42.llmgateway.relay.ChatRelayService
import stillframe42.llmgateway.relay.GatewayMetrics
import stillframe42.llmgateway.routing.ModelRouter

/**
 * 2단계 시맨틱 캐싱 오케스트레이션 (weekly-plan Phase 3) — 정확 일치 → 의미 유사도 → 중계.
 * 저장소 상호작용·직렬화는 ExactResponseCache/SemanticResponseCache 소관 — 여기는 순서·판정·메트릭만.
 * 제외 규칙: tool calling 요청(정의·이력 모두 — 상태 의존)과 X-Cache-Control: no-cache 는 우회.
 */
@Service
class CachingChatService(
    private val router: ModelRouter,
    private val relay: ChatRelayService,
    private val exactCache: ExactResponseCache,
    private val semanticCache: SemanticResponseCache,
    private val metrics: GatewayMetrics,
) {

    fun complete(request: ChatCompletionRequest, taskType: String?, cacheControl: String?): CachedChatResult {
        val route = router.resolve(taskType, request.model)
        if (!cacheable(request, cacheControl)) {
            return finish(relay.relay(request, route), CacheStatus.BYPASS, taskType)
        }

        val key = exactCache.keyOf(route, request)
        exactCache.find(key)?.let { return hit(it, CacheStatus.EXACT_HIT, taskType) }

        semanticCache.findSimilar(request, route)?.let { cached ->
            // 의미 캐시 히트를 정확 캐시로 승격 — 같은 정확 요청의 다음 조회는 임베딩 없이 적중
            exactCache.save(key, cached)
            return hit(cached, CacheStatus.SEMANTIC_HIT, taskType)
        }

        val response = relay.relay(request, route)
        exactCache.save(key, response)
        semanticCache.save(request, route, taskType, response)
        return finish(response, CacheStatus.MISS, taskType)
    }

    private fun cacheable(request: ChatCompletionRequest, cacheControl: String?): Boolean {
        if (cacheControl?.equals("no-cache", ignoreCase = true) == true) return false
        if (!request.tools.isNullOrEmpty()) return false
        // tool 이력 포함 대화 — 도구 실행 결과에 의존하는 응답은 재사용 불가
        if (request.messages.any { it.role == "tool" || !it.toolCalls.isNullOrEmpty() }) return false
        return true
    }

    private fun hit(cached: ChatCompletionResponse, status: CacheStatus, taskType: String?): CachedChatResult {
        metrics.cacheSaved(cached.model, cached.usage)
        return finish(cached, status, taskType)
    }

    private fun finish(response: ChatCompletionResponse, status: CacheStatus, taskType: String?): CachedChatResult {
        metrics.cache(status, taskType)
        return CachedChatResult(response, status)
    }
}
