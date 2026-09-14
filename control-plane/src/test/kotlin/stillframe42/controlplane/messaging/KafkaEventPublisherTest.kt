package stillframe42.controlplane.messaging

import java.util.concurrent.CompletableFuture
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.springframework.kafka.KafkaException
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult

class KafkaEventPublisherTest {

    /** 브로커 부재 시 send() 는 비동기 실패가 아니라 동기 예외를 던진다 (metadata max.block 초과 — DAY 20 실측). */
    private class ThrowingTemplate : KafkaTemplate<String, String>(DefaultKafkaProducerFactory(emptyMap())) {
        override fun send(topic: String, data: String?): CompletableFuture<SendResult<String, String>> =
            throw KafkaException("Send failed")

        override fun send(topic: String, key: String, data: String?): CompletableFuture<SendResult<String, String>> =
            throw KafkaException("Send failed")
    }

    /** 동기 접수 성공(비동기 결과 미도착) — 브로커 정상 시의 send() 반환 형태. */
    private class PendingTemplate : KafkaTemplate<String, String>(DefaultKafkaProducerFactory(emptyMap())) {
        override fun send(topic: String, data: String?): CompletableFuture<SendResult<String, String>> =
            CompletableFuture()

        override fun send(topic: String, key: String, data: String?): CompletableFuture<SendResult<String, String>> =
            CompletableFuture()
    }

    @Test
    fun `동기 send 예외는 전파하지 않고 false 를 반환한다 - 호출자가 롤백 근거로 쓴다`() {
        val publisher = KafkaEventPublisher(ThrowingTemplate())

        assertThatCode {
            assertFalse(publisher.publish("ops.incidents", "inc-1", "{}"))
            assertFalse(publisher.publish("ops.alerts.raw", null, "{}"))
        }.doesNotThrowAnyException()
    }

    @Test
    fun `동기 접수 성공은 true - 비동기 실패는 이 반환값이 못 잡는 한계를 계약으로 남긴다`() {
        val publisher = KafkaEventPublisher(PendingTemplate())

        assertTrue(publisher.publish("ops.incidents", "inc-1", "{}"))
        assertTrue(publisher.publish("ops.alerts.raw", null, "{}"))
    }
}
