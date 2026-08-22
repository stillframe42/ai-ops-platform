package stillframe42.llmgateway.relay

import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlin.test.Test
import kotlin.test.assertTrue
import stillframe42.llmgateway.cache.CacheStatus

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
}
