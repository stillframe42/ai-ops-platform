package stillframe42.controlplane.alert.event

import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * EventPublisher 의 Kafka 구현 — 발행 실패는 로그로만 남긴다: Kafka 다운이 webhook 202
 * 수신을 막으면 안 된다 (compose 에 depends_on 을 걸지 않는 것과 같은 설계 경로).
 * send() 는 브로커 부재 시 비동기 실패가 아니라 동기 예외를 던진다 (metadata max.block 초과 —
 * DAY 20 실측: 이 경로가 새면 webhook 처리 전체가 중단돼 인시던트화 자체가 유실된다).
 */
@Component
class KafkaEventPublisher(private val kafkaTemplate: KafkaTemplate<String, String>) : EventPublisher {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun publish(topic: String, key: String?, payload: String) {
        runCatching {
            // send 의 key 파라미터가 non-null 시그니처 — null key(파티셔너 위임)는 2-인자 오버로드로
            val future = if (key == null) kafkaTemplate.send(topic, payload) else kafkaTemplate.send(topic, key, payload)
            future.whenComplete { _, ex ->
                if (ex != null) {
                    logger.warn("Kafka 발행 실패 — topic={} key={}: {}", topic, key, ex.message)
                }
            }
        }.onFailure {
            logger.warn("Kafka 발행 실패(동기) — topic={} key={}: {}", topic, key, it.message)
        }
    }
}
