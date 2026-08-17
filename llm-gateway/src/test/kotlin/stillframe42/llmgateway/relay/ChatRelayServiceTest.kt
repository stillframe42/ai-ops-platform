package stillframe42.llmgateway.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.ai.chat.messages.MessageType
import stillframe42.llmgateway.api.ChatMessage

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
    fun `알 수 없는 role 은 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            ChatRelayService.toSpringMessages(listOf(ChatMessage("tool", "결과")))
        }
    }
}
