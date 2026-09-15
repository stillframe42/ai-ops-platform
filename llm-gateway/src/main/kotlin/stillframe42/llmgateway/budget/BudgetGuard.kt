package stillframe42.llmgateway.budget

import java.time.Clock
import java.time.LocalDate
import stillframe42.llmgateway.relay.GatewayMetrics
import stillframe42.llmgateway.routing.Provider
import stillframe42.llmgateway.routing.Route

/**
 * 일별 예산 판정·정산 — 요청 전 enforce(한도 초과 시 다운그레이드),
 * 응답 후 settle(실지출 가산 + 80%/100% 임계 경고 1회씩).
 * 일자 구분은 UTC — 프로바이더 청구 기준과 정합.
 */
class BudgetGuard(
    private val budgetProperties: BudgetProperties,
    private val budgetCounter: BudgetCounter,
    private val budgetAlerter: BudgetAlerter,
    private val gatewayMetrics: GatewayMetrics,
    private val clock: Clock,
) {

    fun enforce(route: Route, service: String): BudgetDecision {
        val limit = budgetProperties.dailyLimitUsd ?: return BudgetDecision(route, downgraded = false)
        val day = LocalDate.now(clock)
        val overTotal = budgetCounter.current(totalScope(day)) >= limit
        val overService = budgetProperties.serviceDailyLimitUsd[service]
            ?.let { budgetCounter.current(serviceScope(service, day)) >= it } == true
        if ((!overTotal && !overService) || route.model == budgetProperties.downgrade.model) {
            return BudgetDecision(route, downgraded = false)
        }

        gatewayMetrics.budgetDowngrade(service, route.model)
        // variant 배정은 다운그레이드를 넘어 유지한다 — 실험군 요청이 대조군 비용·캐시로 섞이지 않도록 (다운그레이드 사실은 헤더가 따로 표시)
        val downgraded = Route(
            taskType = route.taskType,
            provider = Provider.valueOf(budgetProperties.downgrade.provider.uppercase()),
            model = budgetProperties.downgrade.model,
            maxTokens = budgetProperties.downgrade.maxTokens,
            variant = route.variant,
        )
        return BudgetDecision(downgraded, downgraded = true)
    }

    fun settle(service: String, costUsd: Double) {
        val limit = budgetProperties.dailyLimitUsd ?: return
        if (costUsd <= 0.0) return
        val day = LocalDate.now(clock)
        val total = budgetCounter.add(totalScope(day), costUsd)
        budgetCounter.add(serviceScope(service, day), costUsd)

        if (total >= limit * budgetProperties.warnRatio && budgetCounter.markOnce("alert:warn:$day")) {
            budgetAlerter.alert(
                ":warning: LLM 일 예산 ${(budgetProperties.warnRatio * 100).toInt()}% 도달 — " +
                    "누계 ${fmt(total)} / 한도 ${fmt(limit)} (UTC $day)",
            )
        }
        if (total >= limit && budgetCounter.markOnce("alert:over:$day")) {
            budgetAlerter.alert(
                ":rotating_light: LLM 일 예산 100% 도달 — 누계 ${fmt(total)} / 한도 ${fmt(limit)} (UTC $day). " +
                    "이후 요청은 저비용 모델(${budgetProperties.downgrade.model})로 다운그레이드됩니다 (차단 없음)",
            )
        }
    }

    private fun totalScope(day: LocalDate) = "total:$day"

    private fun serviceScope(service: String, day: LocalDate) = "service:$service:$day"

    private fun fmt(usd: Double) = "$%.4f".format(usd)
}
