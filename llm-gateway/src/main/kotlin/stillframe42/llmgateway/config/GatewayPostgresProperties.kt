package stillframe42.llmgateway.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 게이트웨이 영속 저장(PostgreSQL llmgateway DB) 접속 — url 부재 = 비구성 (키-게이트 관례).
 * Phase 3 의 gateway.cache.semantic.url 을 승격: 의미 캐시와 비용 원장(Phase 4)이 같은 DB 를
 * 공유하므로 접속 소유를 캐시가 아니라 게이트웨이 수준에 둔다.
 */
@ConfigurationProperties("gateway.postgres")
data class GatewayPostgresProperties(
    val url: String? = null,
    val username: String? = null,
    val password: String? = null,
)
