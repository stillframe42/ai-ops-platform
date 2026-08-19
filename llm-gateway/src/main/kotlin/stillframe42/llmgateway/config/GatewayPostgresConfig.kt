package stillframe42.llmgateway.config

import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

/** 게이트웨이 전용 DB(llmgateway) 접속 — 의미 캐시(pgvector)·비용 원장 공용 (기본 프로파일·테스트는 미구성) */
@Configuration
@ConditionalOnProperty("gateway.postgres.url")
class GatewayPostgresConfig {

    @Bean
    fun gatewayDataSource(properties: GatewayPostgresProperties): DataSource = HikariDataSource().apply {
        jdbcUrl = properties.url
        username = properties.username
        password = properties.password
        maximumPoolSize = 4
    }

    @Bean
    fun gatewayJdbcTemplate(gatewayDataSource: DataSource) = JdbcTemplate(gatewayDataSource)
}
