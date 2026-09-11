"""OTel 텔레메트리 — 전파 + OTLP 전송 (agent-service `otel.py` 이식, service.name=evaluation-service).

전파: httpx 계측이 나가는 요청(게이트웨이 Judge 호출·Prometheus/Loki 재조회)에 W3C traceparent 를 주입한다.
소비 스팬(`ops.analysis.results process`)의 부모는 agent-service 발행 헤더라 평가가 인시던트 trace 에 붙는다.
전송: OTLP 엔드포인트가 설정된 경우에만 Collector 로 보낸다 (키-게이트 — 미설정 = 생성만, 전송 0).
시그널 3종 — 트레이스(평가 스팬) · 메트릭(`aiops.evaluation.score` 히스토그램, 앵커 경계 버킷) · 로그(`gen_ai.evaluation.result`
이벤트). 백엔드(Tempo·Prometheus·Loki)는 Collector 설정 소관 (ADR-0018).
"""

import logging
import os
import socket

from opentelemetry import _logs, metrics, trace
from opentelemetry.sdk._logs import LoggerProvider, LogRecordProcessor
from opentelemetry.sdk._logs.export import BatchLogRecordProcessor
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import MetricReader
from opentelemetry.sdk.metrics.view import ExplicitBucketHistogramAggregation, View
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor, SpanProcessor

from evaluation.config.otel_evaluation import METRIC_SCORE, SCORE_BUCKET_BOUNDARIES
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


def build_log_processor(otlp_endpoint: str | None) -> LogRecordProcessor | None:
    if not otlp_endpoint:
        return None
    from opentelemetry.exporter.otlp.proto.http._log_exporter import OTLPLogExporter

    return BatchLogRecordProcessor(OTLPLogExporter(endpoint=f"{otlp_endpoint.rstrip('/')}/v1/logs"))


def build_meter_provider(resource: Resource, readers: list[MetricReader]) -> MeterProvider:
    """점수 히스토그램의 버킷을 앵커 경계로 고정 — 기본 버킷(지연용 5·10·25…)은 0~1 점수에 맞지 않는다."""
    score_view = View(instrument_name=METRIC_SCORE, aggregation=ExplicitBucketHistogramAggregation(SCORE_BUCKET_BOUNDARIES))
    return MeterProvider(resource=resource, metric_readers=readers, views=[score_view])


def setup_telemetry(
    otlp_endpoint: str | None = None,
    *,
    metric_reader: MetricReader | None = None,
    log_processor: LogRecordProcessor | None = None,
) -> None:
    """전역 Tracer/Meter/Logger provider 설정 + httpx 계측 — 멱등 (이미 SDK provider 면 재진입 무시).

    metric_reader·log_processor 는 테스트 전용 주입 — 전역 provider 는 한 번만 세울 수 있어 인메모리 리더를 처음에 넣는다.
    """
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

    readers = [r for r in (build_metric_reader(otlp_endpoint), metric_reader) if r is not None]
    if readers:
        metrics.set_meter_provider(build_meter_provider(resource, readers))

    log_processors = [lp for lp in (build_log_processor(otlp_endpoint), log_processor) if lp is not None]
    if log_processors:
        logger_provider = LoggerProvider(resource=resource)
        for lp in log_processors:
            logger_provider.add_log_record_processor(lp)
        _logs.set_logger_provider(logger_provider)

    # 계측기 import 를 함수 안에서 — 비활성 경로(단위 테스트 대부분)에서 전역 패치를 만들지 않는다
    from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor

    response_hook, async_response_hook = build_gateway_header_hooks(enricher)
    HTTPXClientInstrumentor().instrument(response_hook=response_hook, async_response_hook=async_response_hook)
    if processor is None:
        logger.info("OTel 트레이스 전파 활성 — OTLP 엔드포인트 미설정 (전송 없음)")
    else:
        logger.info("OTel 트레이스 전파 + 전송 활성 — collector=%s", otlp_endpoint)
