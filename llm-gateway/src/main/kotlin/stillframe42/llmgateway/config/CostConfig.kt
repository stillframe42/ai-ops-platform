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

        // 스키마 준비는 배선 소관 — 저장소 클래스는 append 만 (8/19 검토: 생성자 I/O·책임 분리).
        // 멱등 DDL 을 기동 시 실행하는 수명주기는 pgvector initializeSchema 와 대칭,
        // 버전 관리(Flyway)는 첫 스키마 변경 시점에 도입 (그때 pgvector 테이블까지 함께 인수)
        @Bean
        fun jdbcCostLedger(gatewayJdbcTemplate: JdbcTemplate): CostLedger {
            gatewayJdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS llm_cost_ledger (
                    id BIGSERIAL PRIMARY KEY,
                    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    day DATE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')::date,
                    service TEXT NOT NULL,
                    task TEXT,
                    provider TEXT NOT NULL,
                    model TEXT NOT NULL,
                    cache_status TEXT NOT NULL,
                    prompt_tokens INT NOT NULL,
                    completion_tokens INT NOT NULL,
                    cost_usd NUMERIC(12, 6) NOT NULL,
                    saved_usd NUMERIC(12, 6) NOT NULL
                )
                """.trimIndent(),
            )
            // 집계 차원 질의(서비스별/일별)용 — 태스크·모델은 스캔 규모상 인덱스 불요 (데모 규모)
            gatewayJdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_llm_cost_ledger_day_service ON llm_cost_ledger (day, service)",
            )
            return JdbcCostLedger(gatewayJdbcTemplate)
        }
    }
}
