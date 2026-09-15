package stillframe42.llmgateway.cost

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 비용 원장의 PostgreSQL 구현 (llmgateway DB 공용 — GatewayPostgresConfig).
 * 기록 실패는 로그만 — 원장 장애가 LLM 중계를 막으면 안 된다 (캐시 fail-open 관례).
 * 스키마 준비는 배선(CostConfig) 소관 — 이 클래스는 행 추가만 안다 (8/19 검토: 생성자 I/O 분리).
 */
class JdbcCostLedger(
    private val jdbcTemplate: JdbcTemplate,
) : CostLedger {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun append(entry: CostEntry) {
        runCatching {
            jdbcTemplate.update(
                """
                INSERT INTO llm_cost_ledger
                    (service, task, provider, model, cache_status, prompt_tokens, completion_tokens, cost_usd, saved_usd, variant)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                entry.service,
                entry.task,
                entry.provider,
                entry.model,
                entry.cacheStatus.name.lowercase(),
                entry.promptTokens,
                entry.completionTokens,
                entry.costUsd,
                entry.savedUsd,
                entry.variant,
            )
        }.onFailure {
            logger.warn("비용 원장 기록 실패 — 메트릭은 유지: {}", it.message)
        }
    }
}
