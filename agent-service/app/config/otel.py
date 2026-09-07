"""OTel 텔레메트리 — 전파(DAY 34) + OTLP 전송(DAY 42) + gen_ai 클라이언트 계측·메트릭(DAY 43).

전파: httpx 계측이 나가는 요청(openai SDK 의 게이트웨이 호출·MCP·Prometheus/Loki 조회)에 W3C traceparent 를
주입하고, 인시던트 루트 스팬(runtime 몫)이 실행 1건의 호출 전부를 같은 traceId 로 묶는다.
계측: openai-v2 계측기가 LLM 호출마다 GenAI 컨벤션의 `chat {model}` 클라이언트 스팬과 토큰·지연 메트릭을 만든다
(docs/otel-genai-mapping.md §3). 프롬프트 본문 캡처는 env `OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT`
(§6 — 캡처 지점이 게이트웨이 마스킹보다 앞이라 원문이다. 로컬 SPAN_ONLY, 운영 미설정).
전송: OTLP 엔드포인트가 설정된 경우에만 트레이스·메트릭을 Collector 로 보낸다 (키-게이트 — 미설정 = 생성만, 전송 0).
백엔드(Tempo·Langfuse·Prometheus)는 Collector 설정 소관이라 앱은 Collector 주소만 안다 (§2).
"""

import logging
import os

from opentelemetry import metrics, trace
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import MetricReader
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor, SpanProcessor

from app.config.otel_genai import GenAiSpanEnricher, build_gateway_header_hooks

logger = logging.getLogger(__name__)

# 계측기 세대 고정 (mapping §1 pin) — openai-v2 2.4b0 은 이 값 없이도 신규 경로지만, 버전을 올려도 경로가
# 바뀌지 않도록 명시한다. 환경에서 다른 값을 준 경우는 존중 (setdefault)
SEMCONV_OPT_IN_ENV = "OTEL_SEMCONV_STABILITY_OPT_IN"
SEMCONV_OPT_IN_VALUE = "gen_ai_latest_experimental"
METRIC_EXPORT_INTERVAL_MS = 15_000  # Prometheus scrape 주기와 동일 — 실측 대기 시간 최소화


def build_span_processor(otlp_endpoint: str | None) -> SpanProcessor | None:
    """엔드포인트가 없으면 None — 있으면 Collector 의 OTLP/HTTP 트레이스 경로로 보내는 배치 processor.

    exporter import 를 함수 안에서 하는 이유: 비활성 경로(단위 테스트 대부분)에서 exporter 모듈을 적재하지 않기 위해서다.
    """
    if not otlp_endpoint:
        return None
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter

    exporter = OTLPSpanExporter(endpoint=f"{otlp_endpoint.rstrip('/')}/v1/traces")
    return BatchSpanProcessor(exporter)


def build_metric_reader(otlp_endpoint: str | None) -> MetricReader | None:
    """엔드포인트가 없으면 None — 있으면 Collector 의 OTLP/HTTP 메트릭 경로로 주기 전송하는 reader.

    Collector 가 prometheus exporter 로 노출하고 Prometheus 가 긁는다 — `gen_ai_client_token_usage` 등 (mapping §2).
    """
    if not otlp_endpoint:
        return None
    from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
    from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader

    exporter = OTLPMetricExporter(endpoint=f"{otlp_endpoint.rstrip('/')}/v1/metrics")
    return PeriodicExportingMetricReader(exporter, export_interval_millis=METRIC_EXPORT_INTERVAL_MS)


def setup_telemetry(otlp_endpoint: str | None = None) -> None:
    """전역 Tracer/Meter provider 설정 + httpx·openai 계측 — 멱등 (이미 SDK provider 면 재진입 무시).

    순서: provider 등록 → 계측기 활성. 계측기는 활성 시점의 전역 provider 를 잡으므로 뒤집으면 스팬이 no-op 이 된다.
    """
    if isinstance(trace.get_tracer_provider(), TracerProvider):
        return
    os.environ.setdefault(SEMCONV_OPT_IN_ENV, SEMCONV_OPT_IN_VALUE)
    resource = Resource.create({"service.name": "agent-service"})

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
    from opentelemetry.instrumentation.openai_v2 import OpenAIInstrumentor

    response_hook, async_response_hook = build_gateway_header_hooks(enricher)
    HTTPXClientInstrumentor().instrument(
        response_hook=response_hook, async_response_hook=async_response_hook
    )
    OpenAIInstrumentor().instrument()
    if processor is None:
        logger.info("OTel 트레이스 전파 + gen_ai 계측 활성 — OTLP 엔드포인트 미설정 (전송 없음)")
    else:
        logger.info(
            "OTel 트레이스 전파 + gen_ai 계측 + 전송 활성 — collector=%s, capture=%s",
            otlp_endpoint,
            os.environ.get("OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT") or "NO_CONTENT",
        )
