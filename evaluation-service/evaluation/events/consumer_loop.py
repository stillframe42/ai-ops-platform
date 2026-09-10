"""aiokafka 소비 루프 공통기 — 접속 백오프 + 배치 소비 + 수동 커밋 (at-least-once). agent-service `consumer_loop.py` 이식.

처리 실패(예외 결과)는 커밋 보류 신호, 건너뜀은 정상 종료(문자열 라벨). 미커밋이어도 같은 세션에선
재전달되지 않는 aiokafka 특성 때문에 seek_to_committed 되감기가 명시적으로 필요하다 (DAY 18 실측).
"""

import asyncio
import logging

logger = logging.getLogger(__name__)

# 브로커 접속 실패 시 재시도 간격 — 앱 기동은 유지하고 컨슈머만 재시도 (부분 기능 관례)
RECONNECT_BACKOFF_SECONDS = 10.0


async def start_with_backoff(consumer, producer, bootstrap: str) -> None:
    """브로커 다운 중에도 앱은 뜬다 — 접속만 백오프 무한 재시도."""
    while True:
        try:
            await producer.start()
            await consumer.start()
            return
        except Exception as exc:  # noqa: BLE001 — aiokafka 접속 예외 계열이 넓다
            logger.warning("Kafka 접속 실패 (%s) — %.0fs 후 재시도: %r", bootstrap, RECONNECT_BACKOFF_SECONDS, exc)
            await asyncio.sleep(RECONNECT_BACKOFF_SECONDS)


async def consume_batches(consumer, process, *, max_records: int, label: str) -> None:
    """getmany 배치 → 동시 처리 → 배치 전체 성공 시에만 commit. 취소로 종료된다."""
    while True:
        batches = await consumer.getmany(timeout_ms=1000, max_records=max_records)
        messages = [msg for records in batches.values() for msg in records]
        if not messages:
            continue
        outcomes = await asyncio.gather(
            # 헤더는 트레이스 전파용 (traceparent) — 처리기가 부모 컨텍스트로 복원한다
            *(process(msg.value, msg.headers) for msg in messages),
            return_exceptions=True,
        )
        failures = [o for o in outcomes if isinstance(o, BaseException)]
        if failures:
            # 인프라 실패 — 커밋 보류 + 커밋 지점으로 되감아 배치 재수신. 중복 재전달은 처리 측 멱등이 흡수한다
            logger.warning("[%s] 배치 처리 실패 %d건 — 커밋 보류 후 재수신: %r", label, len(failures), failures[0])
            for tp in consumer.assignment():
                await consumer.seek_to_committed(tp)
            await asyncio.sleep(RECONNECT_BACKOFF_SECONDS)
            continue
        await consumer.commit()
        logger.info("[%s] 배치 %d건 처리·커밋 — %s", label, len(messages), list(outcomes))
