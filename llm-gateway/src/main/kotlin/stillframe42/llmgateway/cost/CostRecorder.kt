package stillframe42.llmgateway.cost

import stillframe42.llmgateway.api.ChatCompletionResponse
import stillframe42.llmgateway.cache.CacheStatus
import stillframe42.llmgateway.relay.GatewayMetrics
import stillframe42.llmgateway.routing.Route

/**
 * 요청별 비용 기록 — CostTrackingAdvisor 개념 재구현.
 * 캐시 히트 = 지출 0·절감액 기록, 미스·우회 = 실비용 기록. 반환값은 예산 카운터 가산분.
 * costLedger 는 원장 비구성(로컬·테스트 — DB 없음)일 때 null (키-게이트 관례).
 */
class CostRecorder(
    private val costCalculator: CostCalculator,
    private val costLedger: CostLedger?,
    private val gatewayMetrics: GatewayMetrics,
) {

    fun record(service: String, route: Route, response: ChatCompletionResponse, status: CacheStatus): Double {
        val amount = costCalculator.costOf(response.model, response.usage.promptTokens, response.usage.completionTokens)
        val hit = status == CacheStatus.EXACT_HIT || status == CacheStatus.SEMANTIC_HIT
        val cost = if (hit) 0.0 else amount
        val saved = if (hit) amount else 0.0

        if (cost > 0.0) gatewayMetrics.costUsd(service, route.taskType, response.model, route.variant, cost)
        if (saved > 0.0) gatewayMetrics.costSavedUsd(service, route.taskType, response.model, route.variant, saved)
        costLedger?.append(
            CostEntry(
                service = service,
                task = route.taskType,
                provider = route.provider.name.lowercase(),
                model = response.model,
                cacheStatus = status,
                promptTokens = response.usage.promptTokens,
                completionTokens = response.usage.completionTokens,
                costUsd = cost,
                savedUsd = saved,
                variant = route.variant,
            ),
        )
        return cost
    }
}
