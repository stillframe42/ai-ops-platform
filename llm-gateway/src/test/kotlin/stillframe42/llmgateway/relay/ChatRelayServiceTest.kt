package stillframe42.llmgateway.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.chat.messages.ToolResponseMessage
import stillframe42.llmgateway.api.ChatMessage
import stillframe42.llmgateway.api.FunctionCallDto
import stillframe42.llmgateway.api.ToolCallDto

class ChatRelayServiceTest {

    @Test
    fun `role 3종이 Spring AI 메시지 타입으로 매핑된다`() {
        val messages = ChatRelayService.toSpringMessages(
            listOf(
                ChatMessage("system", "너는 AIOps 분석가다"),
                ChatMessage("user", "heap 이 왜 올라가나"),
                ChatMessage("assistant", "메트릭을 확인하겠다"),
            ),
        )

        assertEquals(
            listOf(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT),
            messages.map { it.messageType },
        )
        assertEquals("heap 이 왜 올라가나", messages[1].text)
    }

    @Test
    fun `content 블록 배열도 텍스트로 정규화된다 - langchain 1_x 전송 형태`() {
        val messages = ChatRelayService.toSpringMessages(
            listOf(
                ChatMessage("user", listOf(mapOf("type" to "text", "text" to "heap "), mapOf("type" to "text", "text" to "분석"))),
            ),
        )
        assertEquals("heap 분석", messages.single().text)
    }

    @Test
    fun `tool_calls 를 담은 assistant 이력이 ToolCall 로 복원된다 - ReAct 루프 재전송 경로`() {
        val messages = ChatRelayService.toSpringMessages(
            listOf(
                ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(ToolCallDto("tc_1", "function", FunctionCallDto("query_loki", """{"q":"{}"}"""))),
                ),
                ChatMessage(role = "tool", content = "로그 3건", toolCallId = "tc_1"),
            ),
        )

        val assistant = assertIs<AssistantMessage>(messages[0])
        assertEquals("query_loki", assistant.toolCalls.single().name)
        val toolResult = assertIs<ToolResponseMessage>(messages[1])
        assertEquals("tc_1", toolResult.responses.single().id)
        assertEquals("로그 3건", toolResult.responses.single().responseData)
    }

    @Test
    fun `프로바이더 원문 finish_reason 이 OpenAI 표준값으로 매핑된다`() {
        assertEquals("stop", ChatRelayService.standardFinishReason("end_turn"))
        assertEquals("stop", ChatRelayService.standardFinishReason("STOP_SEQUENCE"))
        assertEquals("length", ChatRelayService.standardFinishReason("max_tokens"))
        assertEquals("tool_calls", ChatRelayService.standardFinishReason("tool_use"))
    }

    @Test
    fun `표준값과 미지의 finish_reason 은 소문자로 그대로 통과한다`() {
        assertEquals("stop", ChatRelayService.standardFinishReason("stop"))
        assertEquals("content_filter", ChatRelayService.standardFinishReason("content_filter"))
        assertNull(ChatRelayService.standardFinishReason(null))
    }

    @Test
    fun `tool 메시지에 tool_call_id 가 없으면 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            ChatRelayService.toSpringMessages(listOf(ChatMessage(role = "tool", content = "결과")))
        }
    }

    @Test
    fun `알 수 없는 role 은 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            ChatRelayService.toSpringMessages(listOf(ChatMessage("developer", "결과")))
        }
    }
}
