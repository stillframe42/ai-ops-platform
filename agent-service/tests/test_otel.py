"""OTel 트레이스 전파 테스트 (Phase 6, 서두 결정 ②) — 계약 2개를 고정한다.

① httpx 계측: 나가는 요청(openai SDK 의 게이트웨이 호출 경로)에 W3C traceparent 가 주입된다.
② 인시던트 루트 스팬: 실행 1건이 스팬 하나로 감싸져, 그 안의 모든 호출이 같은 traceId 를 공유한다
   (langfuse_session_id=incident id 규약의 trace 판). exporter 는 없다 — 검증용 InMemory 만 주입.
"""

import asyncio
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import httpx
from opentelemetry import trace
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from app.config.otel import setup_tracing
from app.supervisor.runtime import GraphRuntime, build_incident


def _exporter() -> InMemorySpanExporter:
    # 전역 provider 는 프로세스당 1회만 설정 가능 — 검증 exporter 는 processor 추가로 붙인다
    setup_tracing()
    exporter = InMemorySpanExporter()
    trace.get_tracer_provider().add_span_processor(SimpleSpanProcessor(exporter))
    return exporter


def test_httpx_request_carries_traceparent():
    """계측된 httpx 클라이언트는 요청마다 traceparent 를 주입한다 — 게이트웨이 수용 확인의 반쪽.

    계측은 실전송 계층(HTTPTransport)을 패치하므로 MockTransport 는 우회한다 — openai SDK 가
    실제로 지나는 경로를 검증하려면 루프백 실서버가 필요하다 (8/22 실측).
    """
    setup_tracing()
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
