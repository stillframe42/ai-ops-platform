package stillframe42.llmgateway.relay

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
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
}
