package stillframe42.controlplane.alert.event

/**
 * 이벤트 발행 경계 — 수집 로직이 KafkaTemplate 에 직접 결합하지 않게 분리한다.
 * 단위 테스트는 기록용 fake, 런타임은 KafkaEventPublisher 가 구현.
 *
 * 반환값 = 동기 접수 성패 (DAY 21 — Exp D 유실 창 해소안 ①의 근거 신호).
 * 브로커의 비동기 실패(delivery timeout)는 이 반환값이 잡지 못하는 알려진 한계 —
 * 잡으려면 Future 전파가 필요해 수집 경로가 비동기 합성으로 복잡해진다 (ADR-0011).
 */
fun interface EventPublisher {
    fun publish(topic: String, key: String?, payload: String): Boolean
}
