package stillframe42.llmgateway.config

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import stillframe42.llmgateway.cost.CostCalculator
import stillframe42.llmgateway.cost.CostLedger
import stillframe42.llmgateway.cost.CostRecorder
import stillframe42.llmgateway.cost.JdbcCostLedger
import stillframe42.llmgateway.relay.GatewayMetrics

/** 비용 집계 배선 — 원장은 게이트웨이 DB 구성 시에만, 메트릭 기록은 항상 */
@Configuration
class CostConfig {

    @Bean
    fun costRecorder(costCalculator: CostCalculator, ledgers: ObjectProvider<CostLedger>, gatewayMetrics: GatewayMetrics) =
        CostRecorder(costCalculator, ledgers.getIfAvailable(), gatewayMetrics)

    @Configuration
    @ConditionalOnProperty("gateway.postgres.url")
    class LedgerConfig {

        // 스키마는 Flyway(`db/migration`) 소유 — Boot 자동 구성이 gatewayDataSource 에 마이그레이션을 적용하고
        // JdbcOperations 빈을 그 뒤로 미룬다. 저장소 클래스는 append 만 (8/19 검토: 생성자 I/O·책임 분리).
        // vector_store 는 Spring AI pgvector 소유(initialize-schema) — control-plane 과 같은 경계
        @Bean
        fun jdbcCostLedger(gatewayJdbcTemplate: JdbcTemplate): CostLedger {
            return JdbcCostLedger(gatewayJdbcTemplate)
        }
    }
}
