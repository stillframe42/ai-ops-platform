package stillframe42.controlplane.alert.event

/**
 * 이벤트 발행 경계 — 수집 로직이 KafkaTemplate 에 직접 결합하지 않게 분리한다.
 * 단위 테스트는 기록용 fake, 런타임은 KafkaEventPublisher 가 구현.
 */
fun interface EventPublisher {
    fun publish(topic: String, key: String?, payload: String)
}
