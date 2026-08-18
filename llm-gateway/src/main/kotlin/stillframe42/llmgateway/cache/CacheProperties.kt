package stillframe42.llmgateway.cache

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("gateway.cache")
data class CacheProperties(
    // 정확 일치 캐시 TTL 1시간 (weekly-plan Phase 3)
    val exactTtl: Duration = Duration.ofHours(1),
    // 의미 유사도 캐시 접속·판정 — url 부재 = 비구성 (기본 프로파일·테스트는 정확 일치 캐시만)
    val semantic: Semantic = Semantic(),
) {
    data class Semantic(
        val url: String? = null,
        val username: String? = null,
        val password: String? = null,
        // 히트 임계값 (weekly-plan Phase 3 — 유사도 0.95 초과만 히트)
        val similarityThreshold: Double = 0.95,
    )
}
