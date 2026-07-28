package stillframe42.controlplane.alert.event

import java.util.concurrent.CompletableFuture
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

    @Test
    fun `동기 send 예외도 전파하지 않는다 - 발행 실패는 로그만 계약`() {
        val publisher = KafkaEventPublisher(ThrowingTemplate())

        assertThatCode { publisher.publish("ops.incidents", "inc-1", "{}") }.doesNotThrowAnyException()
        assertThatCode { publisher.publish("ops.alerts.raw", null, "{}") }.doesNotThrowAnyException()
    }
}
