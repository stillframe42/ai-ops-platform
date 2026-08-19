package stillframe42.llmgateway.budget

import java.time.Clock
import java.time.LocalDate
import stillframe42.llmgateway.relay.GatewayMetrics
import stillframe42.llmgateway.routing.Provider
import stillframe42.llmgateway.routing.Route

data class BudgetDecision(val route: Route, val downgraded: Boolean)

/**
 * 일별 예산 판정·정산 (Phase 4) — 요청 전 enforce(한도 초과 시 다운그레이드),
 * 응답 후 settle(실지출 가산 + 80%/100% 임계 경고 1회씩).
 * 일자 구분은 UTC — 프로바이더 청구 기준과 정합.
 */
class BudgetGuard(
    private val properties: BudgetProperties,
    private val counter: BudgetCounter,
    private val alerter: BudgetAlerter,
    private val metrics: GatewayMetrics,
    private val clock: Clock,
) {

    fun enforce(route: Route, service: String): BudgetDecision {
        val limit = properties.dailyLimitUsd ?: return BudgetDecision(route, downgraded = false)
        val day = LocalDate.now(clock)
        val overTotal = counter.current(totalScope(day)) >= limit
        val overService = properties.serviceDailyLimitUsd[service]
            ?.let { counter.current(serviceScope(service, day)) >= it } == true
        if ((!overTotal && !overService) || route.model == properties.downgrade.model) {
            return BudgetDecision(route, downgraded = false)
        }

        metrics.budgetDowngrade(service, route.model)
        val downgraded = Route(
            taskType = route.taskType,
            provider = Provider.valueOf(properties.downgrade.provider.uppercase()),
            model = properties.downgrade.model,
            maxTokens = properties.downgrade.maxTokens,
        )
        return BudgetDecision(downgraded, downgraded = true)
    }

    fun settle(service: String, costUsd: Double) {
        val limit = properties.dailyLimitUsd ?: return
        if (costUsd <= 0.0) return
        val day = LocalDate.now(clock)
        val total = counter.add(totalScope(day), costUsd)
        counter.add(serviceScope(service, day), costUsd)

        if (total >= limit * properties.warnRatio && counter.markOnce("alert:warn:$day")) {
            alerter.alert(
                ":warning: LLM 일 예산 ${(properties.warnRatio * 100).toInt()}% 도달 — " +
                    "누계 ${fmt(total)} / 한도 ${fmt(limit)} (UTC $day)",
            )
        }
        if (total >= limit && counter.markOnce("alert:over:$day")) {
            alerter.alert(
                ":rotating_light: LLM 일 예산 100% 도달 — 누계 ${fmt(total)} / 한도 ${fmt(limit)} (UTC $day). " +
                    "이후 요청은 저비용 모델(${properties.downgrade.model})로 다운그레이드됩니다 (차단 없음)",
            )
        }
    }

    private fun totalScope(day: LocalDate) = "total:$day"

    private fun serviceScope(service: String, day: LocalDate) = "service:$service:$day"

    private fun fmt(usd: Double) = "$%.4f".format(usd)
}
