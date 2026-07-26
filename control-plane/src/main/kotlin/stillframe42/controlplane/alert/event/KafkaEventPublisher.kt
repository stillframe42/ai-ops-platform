package stillframe42.controlplane.alert.event

import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * EventPublisher 의 Kafka 구현 — 발행 실패는 로그로만 남긴다: Kafka 다운이 webhook 202
 * 수신을 막으면 안 된다 (compose 에 depends_on 을 걸지 않는 것과 같은 설계 경로).
 * 프로듀서 자체 재시도는 delivery.timeout.ms 안에서 클라이언트가 수행 — Phase 7 검증 소재.
 */
@Component
class KafkaEventPublisher(private val kafkaTemplate: KafkaTemplate<String, String>) : EventPublisher {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun publish(topic: String, key: String?, payload: String) {
        // send 의 key 파라미터가 non-null 시그니처 — null key(파티셔너 위임)는 2-인자 오버로드로
        val future = if (key == null) kafkaTemplate.send(topic, payload) else kafkaTemplate.send(topic, key, payload)
        future.whenComplete { _, ex ->
            if (ex != null) {
                logger.warn("Kafka 발행 실패 — topic={} key={}: {}", topic, key, ex.message)
            }
        }
    }
}
