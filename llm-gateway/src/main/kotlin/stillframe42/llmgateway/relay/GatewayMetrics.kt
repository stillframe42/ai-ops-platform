package stillframe42.llmgateway.relay

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import stillframe42.llmgateway.api.TokenUsage
import stillframe42.llmgateway.cache.CacheStatus
import stillframe42.llmgateway.routing.Provider

/**
 * 게이트웨이 자체 관측 — Phase 2 확인 기준("태스크별 상이 모델 호출")의 확인 수단
 * (Phase 0 결정 ⑥: Langfuse 주간 비활성 — Micrometer 대체)
 */
@Component
class GatewayMetrics(
    private val meterRegistry: MeterRegistry,
) {

    fun record(taskType: String?, provider: Provider, model: String) {
        meterRegistry.counter(
            "gateway.requests",
            "task", taskType ?: "none",
            "provider", provider.name.lowercase(),
            "model", model,
        ).increment()
    }

    /** 캐시 판정 분포 (Phase 3) — 히트율 = (exact_hit + semantic_hit) / (전체 - bypass) */
    fun cache(status: CacheStatus, taskType: String?) {
        meterRegistry.counter(
            "gateway.cache.requests",
            "result", status.name.lowercase(),
            "task", taskType ?: "none",
        ).increment()
    }

    /** 캐시 적중으로 아낀 토큰 — 절감 비용 추정 패널의 원천 (단가 환산은 Phase 4 외부화와 연동) */
    fun cacheSaved(model: String, usage: TokenUsage) {
        meterRegistry.counter("gateway.cache.saved.tokens", "model", model, "kind", "prompt")
            .increment(usage.promptTokens.toDouble())
        meterRegistry.counter("gateway.cache.saved.tokens", "model", model, "kind", "completion")
            .increment(usage.completionTokens.toDouble())
    }

    /** 실지출 USD (Phase 4) — 단가는 gateway.cost 외부화 테이블 단일 원천 */
    fun costUsd(service: String, task: String?, model: String, amount: Double) {
        meterRegistry.counter(
            "gateway.cost.usd",
            "service", service,
            "task", task ?: "none",
            "model", model,
        ).increment(amount)
    }

    /** 캐시 히트로 아낀 USD — 대시보드 절감 비용 패널의 표준가 상수를 대체 */
    fun costSavedUsd(service: String, task: String?, model: String, amount: Double) {
        meterRegistry.counter(
            "gateway.cost.saved.usd",
            "service", service,
            "task", task ?: "none",
            "model", model,
        ).increment(amount)
    }

    /** 예산 100% 도달로 저비용 모델 강제 전환된 요청 수 (Phase 4 — 차단 대신 다운그레이드) */
    fun budgetDowngrade(service: String, fromModel: String) {
        meterRegistry.counter("gateway.budget.downgrades", "service", service, "from", fromModel).increment()
    }

    /** 주 프로바이더 장애로 폴백한 요청 수 (Phase 5) — target: 교차 프로바이더명 또는 local */
    fun fallback(from: Provider, target: String) {
        meterRegistry.counter("gateway.fallback", "from", from.name.lowercase(), "target", target).increment()
    }

    /** 분당 한도 초과로 429 반환한 요청 수 (Phase 4 Rate Limiting) */
    fun rateLimited(service: String) {
        meterRegistry.counter("gateway.ratelimit.rejected", "service", service).increment()
    }
}
