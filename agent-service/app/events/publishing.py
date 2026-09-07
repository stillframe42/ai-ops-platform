"""발행 규약의 소유자 — 컨슈머들이 쓰는 발행 클로저를 만든다.

producer 인스턴스는 소유하지 않는다 — 그 수명은 각 컨슈머 수명 함수 몫 (발행이 전부
"소비의 결과"인 현 구조). 자발적 발행 지점(API 경로 등)이 생겨 앱 수명 producer 로
승격할 때 이 모듈이 그 자리가 된다.
"""

import json

from app.events.propagation import inject_headers, producer_span


def make_publisher(producer, topic: str):
    """(key, payload) → topic 발행 클로저 — 직렬화 규약(key utf-8, value JSON)을 한 곳이 소유한다.

    발행 클로저가 컨슈머마다 반복되며 규약이 세 곳에 흩어지던 것을 수렴 (규약 변경 시 여기 한 곳).
    """

    async def publish(key: str, payload: dict) -> None:
        # `{topic} send` 스팬 + traceparent 헤더 동봉 (DAY 43) — control-plane 리스너(observation)가 같은 trace 를 잇는다
        with producer_span(topic):
            await producer.send_and_wait(
                topic, key=key.encode(), value=json.dumps(payload).encode(), headers=inject_headers()
            )

    return publish
