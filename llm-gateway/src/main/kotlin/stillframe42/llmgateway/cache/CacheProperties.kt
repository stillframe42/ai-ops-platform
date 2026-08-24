package stillframe42.llmgateway.cache

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("gateway.cache")
data class CacheProperties(
    // 정확 일치 캐시 TTL 1시간
    val exactTtl: Duration = Duration.ofHours(1),
    // 의미 유사도 캐시 판정 — 활성 여부는 게이트웨이 DB 구성(gateway.postgres.url)이 결정
    val semantic: Semantic = Semantic(),
) {
    data class Semantic(
        // 히트 임계값 — 유사도 0.95 초과만 히트
        val similarityThreshold: Double = 0.95,
    )
}
