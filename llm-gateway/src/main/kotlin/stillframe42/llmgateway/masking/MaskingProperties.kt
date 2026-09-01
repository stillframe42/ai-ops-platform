package stillframe42.llmgateway.masking

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 민감 데이터 마스킹 정책 — LLM 방향 입력(user·tool)에서 시크릿·PII 를 프로바이더에 싣지 않는다.
 * 임베딩 포함(include-embeddings): 임베딩 입력은 RAG 저장 텍스트·의미 캐시 키로 영속되므로 기본 포함.
 */
@ConfigurationProperties("gateway.masking")
data class MaskingProperties(
    val enabled: Boolean = true,
    val includeEmbeddings: Boolean = true,
)
