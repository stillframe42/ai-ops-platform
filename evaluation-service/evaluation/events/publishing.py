"""발행 규약의 소유자 — 직렬화 규약(key utf-8, value JSON)을 한 곳이 소유한다 (agent-service `publishing.py` 이식)."""

import json

from evaluation.events.propagation import inject_headers, producer_span


def make_publisher(producer, topic: str):
    """(key, payload) → topic 발행 클로저."""

    async def publish(key: str, payload: dict) -> None:
        # `{topic} send` 스팬 + traceparent 헤더 동봉 — control-plane 리스너(observation)가 같은 trace 를 잇는다
        with producer_span(topic):
            await producer.send_and_wait(
                topic, key=key.encode(), value=json.dumps(payload).encode(), headers=inject_headers()
            )

    return publish
