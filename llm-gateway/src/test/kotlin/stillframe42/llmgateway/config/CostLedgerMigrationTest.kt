package stillframe42.llmgateway.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

/**
 * 원장 스키마는 Flyway 마이그레이션(`db/migration`)이 소유한다 — 빈 DB 에 순서대로 적용되고, 이미 원장이 있던 DB
 * (baseline 0 에서 다시 V1 부터 실행)에서도 실패하지 않아야 한다. pgvector 없이 SQL 만 보는 검증이라 H2 PostgreSQL 모드.
 */
class CostLedgerMigrationTest {

    private fun dataSource(name: String) = DriverManagerDataSource().apply {
        setDriverClassName("org.h2.Driver")
        url = "jdbc:h2:mem:$name;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        username = "sa"
    }

    private fun migrate(dataSource: javax.sql.DataSource) =
        Flyway.configure().dataSource(dataSource).baselineOnMigrate(true).baselineVersion("0").load().migrate()

    private fun columns(jdbc: JdbcTemplate) = jdbc.queryForList(
        "select column_name from information_schema.columns where table_name = 'llm_cost_ledger'",
        String::class.java,
    ).map { it!!.lowercase() }

    @Test
    fun `빈 DB 에 마이그레이션을 적용하면 원장 테이블과 variant 열이 생긴다`() {
        val ds = dataSource("fresh")

        val result = migrate(ds)

        assertTrue(result.migrationsExecuted >= 2, "V1(원장)·V2(variant) 두 단계 이상이어야 한다: ${result.migrationsExecuted}")
        val columns = columns(JdbcTemplate(ds))
        assertTrue("variant" in columns && "cost_usd" in columns && "day" in columns, columns.toString())
    }

    @Test
    fun `이미 원장이 있던 DB 에서도 실패하지 않는다 - 기동 시 DDL 로 만들어진 기존 환경`() {
        val ds = dataSource("existing")
        // 기동 DDL 시절의 원장 모양 (variant 열까지 이미 있는 환경)
        JdbcTemplate(ds).execute(
            "create table llm_cost_ledger (id bigint primary key, \"day\" date not null, service text not null, " +
                "cost_usd numeric(12, 6) not null, variant text)",
        )
        JdbcTemplate(ds).execute("create index idx_llm_cost_ledger_day_service on llm_cost_ledger (\"day\", service)")

        val result = migrate(ds)

        assertEquals(2, result.migrationsExecuted)
        assertTrue("variant" in columns(JdbcTemplate(ds)))
    }
}
