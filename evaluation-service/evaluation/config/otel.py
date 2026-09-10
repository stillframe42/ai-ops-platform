"""OTel 텔레메트리 — 전파 + OTLP 전송 (agent-service `otel.py` 이식, service.name=evaluation-service).

전파: httpx 계측이 나가는 요청(게이트웨이 Judge 호출·Prometheus/Loki 재조회)에 W3C traceparent 를 주입한다.
소비 스팬(`ops.analysis.results process`)의 부모는 agent-service 발행 헤더라 평가가 인시던트 trace 에 붙는다.
전송: OTLP 엔드포인트가 설정된 경우에만 Collector 로 보낸다 (키-게이트 — 미설정 = 생성만, 전송 0).
백엔드(Tempo·Prometheus)는 Collector 설정 소관 (ADR-0018).
"""

import logging
import os
import socket

from opentelemetry import metrics, trace
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import MetricReader
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor, SpanProcessor

from evaluation.config.otel_genai import GenAiSpanEnricher, build_gateway_header_hooks

logger = logging.getLogger(__name__)

SERVICE_NAME = "evaluation-service"
# 계측기 세대 고정 (docs/otel-genai-mapping.md §1 pin) — agent-service 와 같은 값
SEMCONV_OPT_IN_ENV = "OTEL_SEMCONV_STABILITY_OPT_IN"
SEMCONV_OPT_IN_VALUE = "gen_ai_latest_experimental"
METRIC_EXPORT_INTERVAL_MS = 15_000


def build_resource() -> Resource:
    """service.name + service.instance.id — 복제본 구분 (agent-service 와 같은 이유)."""
    instance_id = os.environ.get("HOSTNAME") or socket.gethostname()
    return Resource.create({"service.name": SERVICE_NAME, "service.instance.id": instance_id})


def build_span_processor(otlp_endpoint: str | None) -> SpanProcessor | None:
    if not otlp_endpoint:
        return None
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter

    return BatchSpanProcessor(OTLPSpanExporter(endpoint=f"{otlp_endpoint.rstrip('/')}/v1/traces"))


def build_metric_reader(otlp_endpoint: str | None) -> MetricReader | None:
    if not otlp_endpoint:
        return None
    from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
    from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader

    exporter = OTLPMetricExporter(endpoint=f"{otlp_endpoint.rstrip('/')}/v1/metrics")
    return PeriodicExportingMetricReader(exporter, export_interval_millis=METRIC_EXPORT_INTERVAL_MS)


def setup_telemetry(otlp_endpoint: str | None = None) -> None:
    """전역 Tracer/Meter provider 설정 + httpx 계측 — 멱등 (이미 SDK provider 면 재진입 무시)."""
    if isinstance(trace.get_tracer_provider(), TracerProvider):
        return
    os.environ.setdefault(SEMCONV_OPT_IN_ENV, SEMCONV_OPT_IN_VALUE)
    resource = build_resource()

    provider = TracerProvider(resource=resource)
    enricher = GenAiSpanEnricher()
    provider.add_span_processor(enricher)
    processor = build_span_processor(otlp_endpoint)
    if processor is not None:
        provider.add_span_processor(processor)
    trace.set_tracer_provider(provider)

    reader = build_metric_reader(otlp_endpoint)
    if reader is not None:
        metrics.set_meter_provider(MeterProvider(resource=resource, metric_readers=[reader]))

    # 계측기 import 를 함수 안에서 — 비활성 경로(단위 테스트 대부분)에서 전역 패치를 만들지 않는다
    from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor

    response_hook, async_response_hook = build_gateway_header_hooks(enricher)
    HTTPXClientInstrumentor().instrument(response_hook=response_hook, async_response_hook=async_response_hook)
    if processor is None:
        logger.info("OTel 트레이스 전파 활성 — OTLP 엔드포인트 미설정 (전송 없음)")
    else:
        logger.info("OTel 트레이스 전파 + 전송 활성 — collector=%s", otlp_endpoint)
