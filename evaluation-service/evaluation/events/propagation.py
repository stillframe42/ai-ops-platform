"""Kafka 구간 트레이스 — 레코드 헤더의 W3C traceparent 전파 + 소비/발행 스팬 (agent-service `propagation.py` 이식).

- 소비: agent-service 가 `ops.analysis.results send` 에 동봉한 헤더에서 부모를 복원해 `{topic} process` CONSUMER 스팬을
  연다 → 평가가 인시던트 trace 의 일부가 된다. 헤더가 없으면 새 trace (최선 노력).
- 발행: `{topic} send` PRODUCER 스팬 안에서 현재 컨텍스트를 헤더로 동봉 → control-plane 리스너가 같은 trace 를 잇는다.
"""

from collections.abc import Iterator
from contextlib import contextmanager

from opentelemetry import context, trace
from opentelemetry.propagate import extract, inject
from opentelemetry.trace import Span, SpanKind

KafkaHeaders = list[tuple[str, bytes]]

_tracer = trace.get_tracer("evaluation-service.kafka")


def inject_headers() -> KafkaHeaders:
    """현재 컨텍스트를 aiokafka 헤더 형식(list[(key, bytes)])으로 — 활성 스팬이 없으면 빈 목록."""
    carrier: dict[str, str] = {}
    inject(carrier)
    return [(key, value.encode()) for key, value in carrier.items()]


def extract_parent_context(headers: KafkaHeaders | None) -> context.Context:
    """레코드 헤더에서 부모 컨텍스트 복원 — 헤더 없음·traceparent 없음이면 빈 컨텍스트(새 trace 시작)."""
    if not headers:
        return context.Context()
    carrier = {key: value.decode(errors="ignore") for key, value in headers if value is not None}
    return extract(carrier)


def _messaging_attributes(topic: str, operation: str) -> dict[str, str]:
    return {
        "messaging.system": "kafka",
        "messaging.destination.name": topic,
        "messaging.operation.type": operation,
    }


@contextmanager
def consumer_span(topic: str, headers: KafkaHeaders | None) -> Iterator[Span]:
    """메시지 1건 처리 = `{topic} process` (CONSUMER) — 부모는 발행 측 헤더."""
    with _tracer.start_as_current_span(
        f"{topic} process",
        context=extract_parent_context(headers),
        kind=SpanKind.CONSUMER,
        attributes=_messaging_attributes(topic, "process"),
    ) as span:
        yield span


@contextmanager
def producer_span(topic: str) -> Iterator[Span]:
    """발행 1건 = `{topic} send` (PRODUCER) — 이 안에서 inject_headers() 를 불러야 이 스팬이 헤더의 부모가 된다."""
    with _tracer.start_as_current_span(
        f"{topic} send", kind=SpanKind.PRODUCER, attributes=_messaging_attributes(topic, "send")
    ) as span:
        yield span
