package stillframe42.llmgateway.config

import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
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
 * 의미 캐시는 gateway.cache.semantic.url 이 있을 때만 구성 — 기본 프로파일(로컬·테스트)은
 * DataSource 없이 기동하고 정확 일치 캐시만으로 동작한다 (키-게이트 관례의 캐시판).
 */
@Configuration
class CacheConfig {

    @Bean
    fun exactMatchCacheStore(redis: StringRedisTemplate): ExactMatchCacheStore = RedisCacheStore(redis)

    @Bean
    fun exactResponseCache(store: ExactMatchCacheStore, properties: CacheProperties, mapper: ObjectMapper) =
        ExactResponseCache(store, properties.exactTtl, mapper)

    @Bean
    fun semanticResponseCache(
        vectorStores: ObjectProvider<VectorStore>,
        properties: CacheProperties,
        mapper: ObjectMapper,
    ) = SemanticResponseCache(vectorStores.getIfAvailable(), properties.semantic.similarityThreshold, mapper)

    @Configuration
    @ConditionalOnProperty("gateway.cache.semantic.url")
    class SemanticCacheConfig {

        // 게이트웨이 전용 DB(llmgateway) — control-plane 의 vector_store 와 임베딩 공간·소유를 분리
        @Bean
        fun semanticCacheDataSource(properties: CacheProperties): DataSource = HikariDataSource().apply {
            jdbcUrl = properties.semantic.url
            username = properties.semantic.username
            password = properties.semantic.password
            maximumPoolSize = 4
        }

        @Bean
        fun semanticCacheVectorStore(semanticCacheDataSource: DataSource, embeddingModel: EmbeddingModel): VectorStore =
            PgVectorStore.builder(JdbcTemplate(semanticCacheDataSource), embeddingModel)
                // text-embedding-3-small 출력 차원 (control-plane 관례와 동일)
                .dimensions(1536)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .vectorTableName("semantic_response_cache")
                .initializeSchema(true)
                .build()
    }
}
