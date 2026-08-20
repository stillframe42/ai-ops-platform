package stillframe42.llmgateway.cache

import org.slf4j.LoggerFactory
import org.springframework.ai.document.DefaultContentFormatter
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import stillframe42.llmgateway.api.ChatCompletionRequest
import stillframe42.llmgateway.api.ChatCompletionResponse
import stillframe42.llmgateway.routing.Route
import tools.jackson.databind.ObjectMapper

/**
 * 의미 유사도 응답 캐시 — 프롬프트 임베딩을 벡터 저장소(pgvector)에 쌓고 유사 질문의 응답을 재사용한다.
 * vectorStore 미구성(null) = 비활성 (기본 프로파일·테스트 — 키-게이트 관례).
 * 저장소 장애는 WARN 후 미적중으로 강등 (무캐시 통과 설계).
 */
class SemanticResponseCache(
    private val vectorStore: VectorStore?,
    private val similarityThreshold: Double,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun findSimilar(request: ChatCompletionRequest, route: Route): ChatCompletionResponse? {
        val store = vectorStore ?: return null
        val document = guarded("검색") {
            store.similaritySearch(
                SearchRequest.builder()
                    .query(promptText(request))
                    .topK(1)
                    .similarityThreshold(similarityThreshold)
                    // 모델 경계 유지 — 다른 모델의 응답은 의미가 같아도 재사용하지 않는다
                    .filterExpression("model == '${route.model}'")
                    .build(),
            )
        }?.firstOrNull() ?: return null
        val json = document.metadata["response"] as? String ?: return null
        return guarded("역직렬화") { objectMapper.readValue(json, ChatCompletionResponse::class.java) }
    }

    fun save(request: ChatCompletionRequest, route: Route, taskType: String?, response: ChatCompletionResponse) {
        val store = vectorStore ?: return
        guarded("저장") {
            val document = Document(
                promptText(request),
                mapOf("model" to route.model, "task" to (taskType ?: "none"), "response" to objectMapper.writeValueAsString(response)),
            )
            // 제약 (DAY 31 실측): OpenAiEmbeddingModel 기본 MetadataMode.EMBED 는 저장 시 metadata 를
            // 임베딩 텍스트에 포함한다 — 검색은 질의 텍스트만 임베딩하므로 제외하지 않으면
            // 같은 문장끼리도 거리 0.30 으로 갈라져 히트가 성립하지 않는다
            document.contentFormatter = EMBED_PROMPT_TEXT_ONLY
            store.add(listOf(document))
        }
    }

    private fun promptText(request: ChatCompletionRequest): String =
        request.messages.joinToString("\n") { "${it.role}: ${it.contentText()}" }

    private fun <T> guarded(operation: String, block: () -> T?): T? =
        runCatching(block).getOrElse {
            log.warn("의미 캐시 {} 실패 — 무캐시 통과: {}", operation, it.message)
            null
        }

    companion object {
        // 임베딩 대상 = 순수 프롬프트 텍스트와 정확 일치 (기본 템플릿의 "\n\n" 접두도 거리 0.037 소모 — DAY 31 실측)
        private val EMBED_PROMPT_TEXT_ONLY = DefaultContentFormatter.builder()
            .withTextTemplate("{content}")
            .withExcludedEmbedMetadataKeys("model", "task", "response")
            .build()
    }
}
