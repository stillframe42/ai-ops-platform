package stillframe42.llmgateway.relay

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import stillframe42.llmgateway.cache.CacheStatus
import stillframe42.llmgateway.routing.Provider

class GatewayMetricsTest {

    @Test
    fun `레이턴시 타이머는 Prometheus 히스토그램 버킷을 발행한다 - 대시보드 분위수의 원천`() {
        // percentile histogram 버킷은 레지스트리 구현에 따라 실체화된다 (Simple 은 미발행) —
        // 대시보드 histogram_quantile 이 질의하는 실물인 Prometheus 노출 형식으로 검증
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val gatewayMetrics = GatewayMetrics(registry)

        gatewayMetrics.latency(gatewayMetrics.startTimer(), CacheStatus.MISS)

        val scraped = registry.scrape()
        assertTrue(scraped.contains("gateway_latency_seconds_bucket"), "버킷 미발행 — 분위수 패널이 공백이 된다:\n$scraped")
        assertTrue(scraped.contains("result=\"miss\""))
    }

    @Test
    fun `요청 카운터는 실험 variant 라벨을 나른다 - 미적용은 none`() {
        val registry = SimpleMeterRegistry()
        val gatewayMetrics = GatewayMetrics(registry)

        gatewayMetrics.record("root-cause-analysis", Provider.ANTHROPIC, "claude-sonnet-5", variant = null)
        gatewayMetrics.record("root-cause-analysis", Provider.ANTHROPIC, "claude-haiku-4-5", variant = "analysis-model-haiku:B")

        assertEquals(1.0, registry.get("gateway.requests").tags("variant", "none").counter().count(), 1e-9)
        assertEquals(1.0, registry.get("gateway.requests").tags("variant", "analysis-model-haiku:B").counter().count(), 1e-9)
    }
}
