package stillframe42.llmgateway.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 서비스별 분당 요청 한도 — default-rpm 부재 = 비활성 (키-게이트 관례,
 * 로컬 개발은 Redis 없이 기동). 서비스 구분은 X-Client-Service 헤더.
 */
@ConfigurationProperties("gateway.ratelimit")
data class RateLimitProperties(
    val defaultRpm: Int? = null,
    val serviceRpm: Map<String, Int> = emptyMap(),
)
