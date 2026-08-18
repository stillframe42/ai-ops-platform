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
    private val registry: MeterRegistry,
) {

    fun record(taskType: String?, provider: Provider, model: String) {
        registry.counter(
            "gateway.requests",
            "task", taskType ?: "none",
            "provider", provider.name.lowercase(),
            "model", model,
        ).increment()
    }

    /** 캐시 판정 분포 (Phase 3) — 히트율 = (exact_hit + semantic_hit) / (전체 - bypass) */
    fun cache(status: CacheStatus, taskType: String?) {
        registry.counter(
            "gateway.cache.requests",
            "result", status.name.lowercase(),
            "task", taskType ?: "none",
        ).increment()
    }

    /** 캐시 적중으로 아낀 토큰 — 절감 비용 추정 패널의 원천 (단가 환산은 Phase 4 외부화와 연동) */
    fun cacheSaved(model: String, usage: TokenUsage) {
        registry.counter("gateway.cache.saved.tokens", "model", model, "kind", "prompt")
            .increment(usage.promptTokens.toDouble())
        registry.counter("gateway.cache.saved.tokens", "model", model, "kind", "completion")
            .increment(usage.completionTokens.toDouble())
    }
}
