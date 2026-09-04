"""OTel 트레이스 — 전파(DAY 34) + OTLP 전송(DAY 42).

전파: httpx 계측이 나가는 요청(openai SDK 의 게이트웨이 호출·MCP·Prometheus/Loki 조회)에 W3C traceparent 를
주입하고, 인시던트 루트 스팬(runtime 몫)이 실행 1건의 호출 전부를 같은 traceId 로 묶는다.
전송: OTLP 엔드포인트가 설정된 경우에만 Collector 로 보낸다 (키-게이트 — 미설정 = 스팬 생성만, 전송 0).
백엔드(Tempo·Langfuse)는 Collector 설정 소관이라 앱은 Collector 주소만 안다 (docs/otel-genai-mapping.md §2).
"""

import logging

from opentelemetry import trace
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor, SpanProcessor

logger = logging.getLogger(__name__)


def build_span_processor(otlp_endpoint: str | None) -> SpanProcessor | None:
    """엔드포인트가 없으면 None — 있으면 Collector 의 OTLP/HTTP 트레이스 경로로 보내는 배치 processor.

    exporter import 를 함수 안에서 하는 이유: 비활성 경로(단위 테스트 대부분)에서 exporter 모듈을 적재하지 않기 위해서다.
    """
    if not otlp_endpoint:
        return None
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter

    exporter = OTLPSpanExporter(endpoint=f"{otlp_endpoint.rstrip('/')}/v1/traces")
    return BatchSpanProcessor(exporter)


def setup_tracing(otlp_endpoint: str | None = None) -> None:
    """전역 TracerProvider 설정 + httpx 계측 — 멱등 (이미 SDK provider 면 재진입 무시).

    langfuse v4 SDK 도 OTel 기반이라 순서가 중요하다: 앱 기동 시 이 함수가 먼저 전역
    provider 를 잡는다 (Langfuse 핸들러 생성은 lifespan 안 — 이 함수 뒤).
    """
    if isinstance(trace.get_tracer_provider(), TracerProvider):
        return
    provider = TracerProvider(resource=Resource.create({"service.name": "agent-service"}))
    processor = build_span_processor(otlp_endpoint)
    if processor is not None:
        provider.add_span_processor(processor)
    trace.set_tracer_provider(provider)
    # 계측기 import 를 함수 안에서 — 비활성 경로(단위 테스트 대부분)에서 전역 패치를 만들지 않는다
    from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor

    HTTPXClientInstrumentor().instrument()
    if processor is None:
        logger.info("OTel 트레이스 전파 활성 — OTLP 엔드포인트 미설정 (전송 없음)")
    else:
        logger.info("OTel 트레이스 전파 + 전송 활성 — collector=%s", otlp_endpoint)
