package stillframe42.llmgateway.cache

import java.security.MessageDigest
import java.time.Duration
import org.slf4j.LoggerFactory
import stillframe42.llmgateway.api.ChatCompletionRequest
import stillframe42.llmgateway.api.ChatCompletionResponse
import stillframe42.llmgateway.routing.Route
import tools.jackson.databind.ObjectMapper

/**
 * 정확 일치 응답 캐시 — 요청 정규화 해시를 키로 직렬화된 응답을 저장·복원한다.
 * 저장소 장애·손상 항목은 WARN 후 미적중으로 강등 (무캐시 통과 설계 — 예외를 밖으로 내지 않는다).
 */
class ExactResponseCache(
    private val store: ExactMatchCacheStore,
    private val ttl: Duration,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 캐시 키 = 응답에 영향을 주는 입력 전부의 해시 — 해석 모델·유효 옵션·메시지 중 하나라도 다르면 다른 항목 */
    fun keyOf(route: Route, request: ChatCompletionRequest): String {
        val effectiveMaxTokens = request.maxTokens ?: route.maxTokens
        val canonical = buildString {
            append(route.provider).append('|').append(route.model).append('|')
            append(effectiveMaxTokens).append('|').append(request.temperature).append('|')
            request.messages.forEach { append(it.role).append(' ').append(it.contentText()).append(' ') }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return "gw:exact:" + digest.joinToString("") { "%02x".format(it) }
    }

    fun find(key: String): ChatCompletionResponse? =
        guarded("조회") { store.get(key) }
            ?.let { guarded("역직렬화") { mapper.readValue(it, ChatCompletionResponse::class.java) } }

    fun save(key: String, response: ChatCompletionResponse) {
        guarded("저장") { store.put(key, mapper.writeValueAsString(response), ttl) }
    }

    private fun <T> guarded(operation: String, block: () -> T?): T? =
        runCatching(block).getOrElse {
            log.warn("정확 캐시 {} 실패 — 무캐시 통과: {}", operation, it.message)
            null
        }
}
