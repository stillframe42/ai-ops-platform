"""OTel 텔레메트리 테스트 — 전파·루트 스팬·gen_ai 클라이언트 스팬 계약을 고정한다.

① httpx 계측: 나가는 요청(openai SDK 의 게이트웨이 호출 경로)에 W3C traceparent 가 주입된다.
② 인시던트 루트 스팬: 실행 1건이 스팬 하나로 감싸져, 그 안의 모든 호출이 같은 traceId 를 공유하고
   세션 축 gen_ai.conversation.id(=incident id)가 하위 스팬에 상속된다.
③ gen_ai 클라이언트 스팬(openai-v2 계측, mapping §3·§7 속성 계약): 요청/응답 모델·토큰·종료 이유 + 게이트웨이
   판정 헤더 승격(gateway.*). 실 OTLP exporter 는 없다 — 검증용 InMemory 만 주입.
"""

import asyncio
import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import httpx
from opentelemetry import trace
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from app.config.otel import setup_telemetry
from app.supervisor.runtime import GraphRuntime, build_incident


def _exporter() -> InMemorySpanExporter:
    # 전역 provider 는 프로세스당 1회만 설정 가능 — 검증 exporter 는 processor 추가로 붙인다
    setup_telemetry()
    exporter = InMemorySpanExporter()
    trace.get_tracer_provider().add_span_processor(SimpleSpanProcessor(exporter))
    return exporter


def test_httpx_request_carries_traceparent():
    """계측된 httpx 클라이언트는 요청마다 traceparent 를 주입한다 — 게이트웨이 수용 확인의 반쪽.

    계측은 실전송 계층(HTTPTransport)을 패치하므로 MockTransport 는 우회한다 — openai SDK 가
    실제로 지나는 경로를 검증하려면 루프백 실서버가 필요하다 (8/22 실측).
    """
    setup_telemetry()
    captured: dict = {}

    class _Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            captured.update({k.lower(): v for k, v in self.headers.items()})
            self.send_response(200)
            self.end_headers()

        def log_message(self, *_args):  # 테스트 출력 오염 방지
            pass

    server = HTTPServer(("127.0.0.1", 0), _Handler)
    threading.Thread(target=server.handle_request, daemon=True).start()
    try:
        with httpx.Client() as client:
            client.get(f"http://127.0.0.1:{server.server_port}/v1/models")
    finally:
        server.server_close()

    assert "traceparent" in captured
    # W3C 형식: 00-<32hex traceId>-<16hex spanId>-<flags> — sampled 비트만 검사
    # (SDK 1.44 는 Level 2 의 random-trace-id 플래그 0x02 를 함께 세워 03 이 된다 — 8/22 실측)
    version, trace_id, span_id, flags = captured["traceparent"].split("-")
    assert (len(trace_id), len(span_id)) == (32, 16)
    assert int(flags, 16) & 0x01 == 0x01, f"unsampled 전파 — 하류 상관이 끊긴다: {flags}"


class _SpanCapturingGraph:
    """ainvoke 시점의 현재 스팬 컨텍스트를 기록하는 스텁 — LLM 호출 없이 스팬 상속만 검증."""

    def __init__(self) -> None:
        self.trace_id: int | None = None

    async def ainvoke(self, *_args, **_kwargs) -> None:
        self.trace_id = trace.get_current_span().get_span_context().trace_id


def test_incident_run_is_wrapped_in_root_span():
    """start() 는 인시던트 루트 스팬 안에서 그래프를 실행한다 — 실행 중 호출이 traceId 를 상속."""
    exporter = _exporter()
    graph = _SpanCapturingGraph()
    runtime = GraphRuntime(graph)
    incident = build_incident("latency-surge", "inc-otel-001")

    asyncio.run(runtime.start(incident))

    span = next(s for s in exporter.get_finished_spans() if s.name == "incident.run")
    assert span.attributes["incident.id"] == "inc-otel-001"
    assert graph.trace_id == span.context.trace_id  # 그래프 실행이 루트 스팬 컨텍스트 안에 있었다


def test_resume_is_wrapped_in_root_span_too():
    """재개(다운 복구·승인 재개)도 같은 규약 — 인시던트 id 속성이 붙은 스팬으로 감싼다."""
    exporter = _exporter()
    runtime = GraphRuntime(_SpanCapturingGraph())

    asyncio.run(runtime.resume("inc-otel-002"))

    span = next(s for s in exporter.get_finished_spans() if s.name == "incident.resume")
    assert span.attributes["incident.id"] == "inc-otel-002"


def test_span_processor_is_absent_without_endpoint():
    """OTLP 엔드포인트 미설정 = 전파만 (현행 유지) — exporter·processor 를 만들지 않는다 (키-게이트 관례)."""
    from app.config.otel import build_span_processor

    assert build_span_processor(None) is None
    assert build_span_processor("") is None


def test_span_processor_targets_collector_traces_path():
    """엔드포인트가 있으면 Batch processor + OTLP/HTTP exporter — Collector 의 /v1/traces 로 향한다."""
    from opentelemetry.sdk.trace.export import BatchSpanProcessor

    from app.config.otel import build_span_processor

    processor = build_span_processor("http://otel-collector:4318")

    assert isinstance(processor, BatchSpanProcessor)
    exporter = processor.span_exporter
    assert exporter._endpoint == "http://otel-collector:4318/v1/traces"
    processor.shutdown()


def test_child_spans_inherit_incident_attributes():
    """루트 스팬의 gen_ai.conversation.id·incident.id 가 하위 스팬(도구·게이트웨이 호출 자리)에 복사된다.

    Langfuse 세션과 Tempo 검색이 이 속성을 스팬 단위로 읽으므로, 루트에만 있으면 LLM 호출 스팬이 세션에 묶이지 않는다.
    """
    exporter = _exporter()
    tracer = trace.get_tracer("test")

    class _ChildSpanGraph:
        async def ainvoke(self, *_args, **_kwargs) -> None:
            with tracer.start_as_current_span("tool.call"):
                with tracer.start_as_current_span("chat default", attributes={"gen_ai.operation.name": "chat"}):
                    pass

    asyncio.run(GraphRuntime(_ChildSpanGraph()).start(build_incident("latency-surge", "inc-otel-003")))

    by_name = {s.name: s for s in exporter.get_finished_spans() if s.attributes.get("incident.id") == "inc-otel-003"}
    assert {"incident.run", "tool.call", "chat default"} <= by_name.keys()
    for span in by_name.values():
        assert span.attributes["gen_ai.conversation.id"] == "inc-otel-003"


_CHAT_COMPLETION = {
    "id": "chatcmpl-test",
    "object": "chat.completion",
    "created": 0,
    "model": "claude-sonnet-5",  # 게이트웨이가 별칭(default)을 실모델로 바꿔 돌려주는 응답 필드
    "choices": [
        {"index": 0, "message": {"role": "assistant", "content": "ok"}, "finish_reason": "stop"}
    ],
    "usage": {"prompt_tokens": 11, "completion_tokens": 3, "total_tokens": 14},
}


def test_openai_chat_span_carries_genai_and_gateway_attributes():
    """openai SDK 호출 1건 = `chat {요청모델}` CLIENT 스팬 — 표준 속성 + 게이트웨이 판정 헤더 승격.

    루프백 서버가 게이트웨이 역할(실모델 응답·X-Gateway-* 헤더)을 한다. 게이트웨이 헤더는 SDK 안쪽 httpx 스팬에
    도착하므로, otel_genai 훅이 부모 `chat` 스팬으로 올려야 Tempo/Langfuse 에서 LLM 호출과 판정이 한 스팬에 보인다.
    """
    from openai import OpenAI

    exporter = _exporter()

    class _Gateway(BaseHTTPRequestHandler):
        def do_POST(self):
            self.rfile.read(int(self.headers.get("Content-Length", 0)))
            body = json.dumps(_CHAT_COMPLETION).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("X-Gateway-Cache", "MISS")
            self.send_header("X-Gateway-Guardrail", "flagged")
            self.send_header("X-Gateway-Guardrail-Stage", "pattern")
            self.send_header("X-Gateway-Downgrade", "haiku")
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_args):
            pass

    server = HTTPServer(("127.0.0.1", 0), _Gateway)
    threading.Thread(target=server.handle_request, daemon=True).start()
    try:
        client = OpenAI(
            base_url=f"http://127.0.0.1:{server.server_port}/v1",
            api_key="gateway-token",
            default_headers={"X-Task-Type": "root-cause-analysis"},
            max_retries=0,
        )
        # LangChain 이 include_response_headers=True 로 쓰는 경로 — 계측기가 LegacyAPIResponse 를 parse 해야 속성이 나온다
        client.chat.completions.with_raw_response.create(
            model="default", messages=[{"role": "user", "content": "ping"}]
        )
    finally:
        server.server_close()

    span = next(s for s in exporter.get_finished_spans() if s.name == "chat default")
    attrs = span.attributes
    assert span.kind == trace.SpanKind.CLIENT
    assert attrs["gen_ai.operation.name"] == "chat"
    assert attrs["gen_ai.provider.name"] == "openai"  # 프록시 경유 — 규격상 보정 없음 (mapping §5)
    assert attrs["gen_ai.request.model"] == "default"
    assert attrs["gen_ai.response.model"] == "claude-sonnet-5"
    assert (attrs["gen_ai.usage.input_tokens"], attrs["gen_ai.usage.output_tokens"]) == (11, 3)
    assert tuple(attrs["gen_ai.response.finish_reasons"]) == ("stop",)
    # 콘텐츠 캡처 기본값 NO_CONTENT — 테스트 프로세스에 env 가 없으므로 본문 속성이 없어야 한다
    assert "gen_ai.input.messages" not in attrs and "gen_ai.output.messages" not in attrs
    assert attrs["gateway.task_type"] == "root-cause-analysis"
    assert attrs["gateway.cache"] == "MISS"
    assert attrs["gateway.guardrail"] == "flagged"
    assert attrs["gateway.guardrail_stage"] == "pattern"
    assert attrs["gateway.downgrade"] == "haiku"
    assert "gateway.fallback" not in attrs  # 헤더가 없는 판정은 속성도 없다


def test_metric_reader_is_absent_without_endpoint():
    """메트릭도 같은 키-게이트 — 엔드포인트 미설정이면 reader·exporter 를 만들지 않는다."""
    from app.config.otel import build_metric_reader

    assert build_metric_reader(None) is None
    assert build_metric_reader("") is None


def test_metric_reader_targets_collector_metrics_path():
    """엔드포인트가 있으면 주기 전송 reader + OTLP/HTTP exporter — Collector 의 /v1/metrics 로 향한다."""
    from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader

    from app.config.otel import build_metric_reader

    reader = build_metric_reader("http://otel-collector:4318")

    assert isinstance(reader, PeriodicExportingMetricReader)
    assert reader._exporter._endpoint == "http://otel-collector:4318/v1/metrics"
    reader.shutdown()
