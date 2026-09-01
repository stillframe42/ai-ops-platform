package stillframe42.llmgateway.masking

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import stillframe42.llmgateway.api.ChatCompletionRequest
import stillframe42.llmgateway.api.ChatMessage
import stillframe42.llmgateway.api.EmbeddingsRequest
import stillframe42.llmgateway.relay.GatewayMetrics

class InputMaskingServiceTest {

    private val registry = SimpleMeterRegistry()

    private fun service(properties: MaskingProperties = MaskingProperties()) =
        InputMaskingService(properties, GatewayMetrics(registry))

    private fun maskingCount(pattern: String) =
        registry.find("gateway.masking").tags("pattern", pattern).counter()?.count() ?: 0.0

    @Test
    fun `user·tool 메시지의 시크릿을 마스킹하고 메트릭을 센다 - system 은 대상 아님`() {
        val request = ChatCompletionRequest(
            messages = listOf(
                ChatMessage("system", "api_key=system-prompt-example-value"),
                ChatMessage("user", "로그에 client_secret=abc123def456 노출"),
                ChatMessage("tool", "설정 덤프: password=hunter2secret", toolCallId = "call-1"),
            ),
        )

        val masked = service().mask(request)

        assertEquals("api_key=system-prompt-example-value", masked.messages[0].contentText())
        assertFalse("abc123def456" in masked.messages[1].contentText())
        assertFalse("hunter2secret" in masked.messages[2].contentText())
        assertEquals(2.0, maskingCount("credential-assignment"))
    }

    @Test
    fun `블록 배열 content 는 text 값만 치환하고 구조를 보존한다 - langchain 형식`() {
        val request = ChatCompletionRequest(
            messages = listOf(
                ChatMessage("user", listOf(mapOf("type" to "text", "text" to "키 sk-ant-api03-abcdefghij1234567890"))),
            ),
        )

        val content = service().mask(request).messages[0].content as List<*>
        val block = content.single() as Map<*, *>

        assertEquals("text", block["type"])
        assertFalse("sk-ant" in block["text"].toString())
    }

    @Test
    fun `민감 정보가 없으면 요청 객체를 그대로 돌려준다 - 불필요한 복사 없음`() {
        val request = ChatCompletionRequest(messages = listOf(ChatMessage("user", "에러율 12% 원인 분석")))

        assertSame(request, service().mask(request))
    }

    @Test
    fun `임베딩 입력도 마스킹한다 - RAG 저장·의미 캐시 키 영속 대비`() {
        val request = EmbeddingsRequest(input = listOf("보고서: access_token=abcdef123456 노출"))

        val masked = service().mask(request)

        assertFalse("abcdef123456" in masked.inputTexts().single())
    }

    @Test
    fun `include-embeddings=false 면 임베딩은 통과한다`() {
        val request = EmbeddingsRequest(input = "access_token=abcdef123456")

        assertSame(request, service(MaskingProperties(includeEmbeddings = false)).mask(request))
    }

    @Test
    fun `enabled=false 면 채팅도 통과한다`() {
        val request = ChatCompletionRequest(messages = listOf(ChatMessage("user", "password=hunter2secret")))

        assertSame(request, service(MaskingProperties(enabled = false)).mask(request))
        assertTrue(registry.find("gateway.masking").counters().isEmpty())
    }
}
