package stillframe42.llmgateway.config

import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.pgvector.PgVectorStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import stillframe42.llmgateway.cache.CacheProperties
import stillframe42.llmgateway.cache.ExactMatchCacheStore
import stillframe42.llmgateway.cache.ExactResponseCache
import stillframe42.llmgateway.cache.RedisCacheStore
import stillframe42.llmgateway.cache.SemanticResponseCache
import tools.jackson.databind.ObjectMapper

/**
 * 2단계 캐시 배선 (Phase 3).
 * 의미 캐시는 게이트웨이 DB(gateway.postgres.url)가 구성될 때만 활성 — 기본 프로파일(로컬·테스트)은
 * DataSource 없이 기동하고 정확 일치 캐시만으로 동작한다 (키-게이트 관례의 캐시판).
 * 스캔이 못 하는 조립만 Config 에 — 값 파라미터는 CacheProperties 에서 골라 넣는 코드가 필요.
 */
@Configuration
class CacheConfig {

    @Bean
    fun exactMatchCacheStore(stringRedisTemplate: StringRedisTemplate): ExactMatchCacheStore = RedisCacheStore(stringRedisTemplate)

    @Bean
    fun exactResponseCache(exactMatchCacheStore: ExactMatchCacheStore, cacheProperties: CacheProperties, objectMapper: ObjectMapper) =
        ExactResponseCache(exactMatchCacheStore, cacheProperties.exactTtl, objectMapper)

    @Bean
    fun semanticResponseCache(
        vectorStores: ObjectProvider<VectorStore>,
        cacheProperties: CacheProperties,
        objectMapper: ObjectMapper,
    ) = SemanticResponseCache(vectorStores.getIfAvailable(), cacheProperties.semantic.similarityThreshold, objectMapper)

    @Configuration
    @ConditionalOnProperty("gateway.postgres.url")
    class SemanticCacheConfig {

        @Bean
        fun semanticCacheVectorStore(gatewayJdbcTemplate: JdbcTemplate, embeddingModel: EmbeddingModel): VectorStore =
            PgVectorStore.builder(gatewayJdbcTemplate, embeddingModel)
                // text-embedding-3-small 출력 차원 (control-plane 관례와 동일)
                .dimensions(1536)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .vectorTableName("semantic_response_cache")
                .initializeSchema(true)
                .build()
    }
}
