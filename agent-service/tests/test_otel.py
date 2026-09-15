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
from types import SimpleNamespace
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


class _Snapshot:
    def __init__(self, values: dict) -> None:
        self.values = values


class _SpanCapturingGraph:
    """ainvoke 시점의 현재 스팬 컨텍스트를 기록하는 스텁 — LLM 호출 없이 스팬 상속만 검증."""

    def __init__(self, state: dict | None = None) -> None:
        self.trace_id: int | None = None
        self.inputs: list = []
        self._state = state or {}

    async def ainvoke(self, inputs, **_kwargs) -> None:
        self.inputs.append(inputs)
        self.trace_id = trace.get_current_span().get_span_context().trace_id

    async def aget_state(self, _config) -> _Snapshot:
        return _Snapshot(self._state)


WORKFLOW = "invoke_workflow incident-response"


def test_incident_run_is_wrapped_in_workflow_span():
    """start() 는 `invoke_workflow incident-response` 루트 스팬 안에서 그래프를 실행하고, 루트 좌표를 상태에 넣는다."""
    exporter = _exporter()
    graph = _SpanCapturingGraph()
    runtime = GraphRuntime(graph)
    incident = build_incident("latency-surge", "inc-otel-001")

    asyncio.run(runtime.start(incident))

    span = next(s for s in exporter.get_finished_spans() if s.attributes.get("incident.id") == "inc-otel-001")
    assert span.name == WORKFLOW
    assert span.attributes["gen_ai.operation.name"] == "invoke_workflow"
    assert span.attributes["gen_ai.workflow.name"] == "incident-response"
    assert span.attributes["gen_ai.conversation.id"] == "inc-otel-001"
    assert span.attributes["aiops.resumed"] is False
    assert graph.trace_id == span.context.trace_id  # 그래프 실행이 루트 스팬 컨텍스트 안에 있었다
    # 체크포인트로 갈 초기 입력에 run 좌표 — 재개 trace 의 link 원천
    assert graph.inputs[0]["run_trace_id"] == format(span.context.trace_id, "032x")
    assert graph.inputs[0]["run_span_id"] == format(span.context.span_id, "016x")


def test_resume_links_to_original_run_span():
    """재개(승인 후·다운 복구)는 새 trace 지만 체크포인트의 run 좌표로 원 실행을 link 한다."""
    exporter = _exporter()
    run_trace, run_span = "0" * 31 + "a", "0" * 15 + "b"
    runtime = GraphRuntime(_SpanCapturingGraph({"run_trace_id": run_trace, "run_span_id": run_span}))

    asyncio.run(runtime.resume("inc-otel-002"))

    span = next(s for s in exporter.get_finished_spans() if s.attributes.get("incident.id") == "inc-otel-002")
    assert span.name == WORKFLOW and span.attributes["aiops.resumed"] is True
    assert span.context.trace_id != int(run_trace, 16)  # 새 trace
    (link,) = span.links
    assert (link.context.trace_id, link.context.span_id) == (int(run_trace, 16), int(run_span, 16))
    assert link.attributes["aiops.link.reason"] == "resume-after-approval"


def test_resume_without_stored_ref_has_no_link():
    """구버전 체크포인트(좌표 없음)로 재개해도 실패하지 않는다 — link 만 없다."""
    exporter = _exporter()
    runtime = GraphRuntime(_SpanCapturingGraph())

    asyncio.run(runtime.resume("inc-otel-004"))

    span = next(s for s in exporter.get_finished_spans() if s.attributes.get("incident.id") == "inc-otel-004")
    assert span.links == ()


def test_start_with_parent_context_joins_upstream_trace():
    """Kafka 헤더에서 복원한 상류 컨텍스트가 있으면 워크플로 스팬이 그 trace 의 자식이 된다."""
    from app.events.propagation import extract_parent_context, inject_headers

    exporter = _exporter()
    tracer = trace.get_tracer("test")
    with tracer.start_as_current_span("control-plane webhook") as upstream:
        headers = inject_headers()  # 발행 측이 레코드에 싣는 헤더
    assert any(key == "traceparent" for key, _ in headers)

    graph = _SpanCapturingGraph()
    asyncio.run(GraphRuntime(graph).start(build_incident("latency-surge", "inc-otel-005"), parent_context=extract_parent_context(headers)))

    span = next(s for s in exporter.get_finished_spans() if s.attributes.get("incident.id") == "inc-otel-005")
    assert span.context.trace_id == upstream.get_span_context().trace_id
    assert span.parent.span_id == upstream.get_span_context().span_id
    assert extract_parent_context(None) is not None  # 헤더 없음 = 빈 컨텍스트 (예외 없음)


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
    assert {WORKFLOW, "tool.call", "chat default"} <= by_name.keys()
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
            self.send_header("X-Gateway-Variant", "analysis-model-haiku:B")
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
    assert attrs["gateway.variant"] == "analysis-model-haiku:B"  # 실험 variant 적용 echo (ADR-0019)
    assert "gateway.fallback" not in attrs  # 헤더가 없는 판정은 속성도 없다


def test_gateway_header_hook_ignores_non_recording_spans():
    """샘플링 제외·SDK off 요청의 httpx 스팬은 NonRecordingSpan — 훅이 예외 없이 건너뛰어야 LLM 호출이 살아남는다.

    httpx 계측은 response_hook 의 예외를 삼키지 않아, 훅이 `span.parent` 로 죽으면 openai SDK 가 APIConnectionError 를 낸다.
    """
    from opentelemetry.trace import INVALID_SPAN_CONTEXT, NonRecordingSpan

    from app.config.otel_genai import GenAiSpanEnricher, build_gateway_header_hooks

    promote, _ = build_gateway_header_hooks(GenAiSpanEnricher())
    request = SimpleNamespace(headers=httpx.Headers({"x-task-type": "monitoring-summary"}))
    response = SimpleNamespace(headers=httpx.Headers({"x-gateway-cache": "miss"}))

    promote(NonRecordingSpan(INVALID_SPAN_CONTEXT), request, response)  # 예외 없이 반환


def test_genai_attribute_inventory_matches_baseline():
    """계측 라이브러리 pin 점검 — 스팬 이름·kind·scope별 속성 키 목록이 baseline 스냅샷과 같아야 한다.

    openai-v2·util-genai·semconv·SDK 버전을 올리면 여기서 diff 가 난다 → docs/otel-genai-mapping.md §3 표를 갱신하고
    `scripts/genai_attribute_inventory.py --json` 출력으로 baseline 을 교체한다 (§7 절차). 값이 아닌 키만 비교한다.
    """
    import importlib.util
    from pathlib import Path

    root = Path(__file__).resolve().parents[1]
    spec = importlib.util.spec_from_file_location("genai_attribute_inventory", root / "scripts" / "genai_attribute_inventory.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    exporter = _exporter()
    exporter.clear()
    current = module.collect_inventory(exporter)
    baseline = json.loads((root / "tests" / "resources" / "genai_attribute_inventory.json").read_text(encoding="utf-8"))
    # 이 프로세스의 다른 테스트가 남긴 스팬은 섞이지 않는다 — clear 후 인벤토리 경로만 실행
    assert current == baseline


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


def test_agent_node_wrapper_emits_invoke_agent_span():
    """노드 래퍼: `invoke_agent {노드}` INTERNAL 스팬 + gen_ai.agent.name·aiops.node, 세션 축은 루트에서 상속."""
    from app.config.agent_spans import agent_node, plain_node

    exporter = _exporter()

    async def monitor(state):
        return {"seen": state["incident"].id}

    def approval(state):
        return {}

    class _NodeGraph:
        async def ainvoke(self, inputs, **_kwargs):
            await agent_node("monitor", monitor)(inputs)
            plain_node("approval", approval)(inputs)

    asyncio.run(GraphRuntime(_NodeGraph()).start(build_incident("latency-surge", "inc-otel-006")))

    spans = {s.name: s for s in exporter.get_finished_spans() if s.attributes.get("incident.id") == "inc-otel-006"}
    agent = spans["invoke_agent monitor"]
    assert agent.kind == trace.SpanKind.INTERNAL
    assert agent.attributes["gen_ai.operation.name"] == "invoke_agent"
    assert agent.attributes["gen_ai.agent.name"] == "monitor"
    assert agent.attributes["aiops.node"] == "monitor"
    assert agent.attributes["gen_ai.conversation.id"] == "inc-otel-006"
    assert spans["approval"].attributes["aiops.node"] == "approval"
    assert "gen_ai.operation.name" not in spans["approval"].attributes  # LLM 없는 노드는 GenAI 스팬 아님


def test_instrumented_tool_emits_execute_tool_span_with_mcp_attributes():
    """도구 래퍼: `execute_tool {도구}` + gen_ai.tool.name/type, MCP 도구는 mcp.method.name·server.* 동반.

    NO_CONTENT 기본이라 인자·결과 속성은 없다. 원 도구의 이름·스키마·반환값은 그대로다.
    """
    from langchain_core.tools import tool

    from app.config.agent_spans import instrumented_tool

    exporter = _exporter()

    @tool
    def get_deployment_history(app: str) -> str:
        """배포 이력."""
        return f"history:{app}"

    local = instrumented_tool(get_deployment_history)
    remote = instrumented_tool(get_deployment_history, mcp_server_url="http://control-plane:8080/mcp")

    assert local.invoke({"app": "target-app"}) == "history:target-app"
    assert asyncio.run(remote.ainvoke({"app": "target-app"})) == "history:target-app"

    local_span, remote_span = [s for s in exporter.get_finished_spans() if s.name == "execute_tool get_deployment_history"][-2:]
    for span in (local_span, remote_span):
        assert span.attributes["gen_ai.operation.name"] == "execute_tool"
        assert span.attributes["gen_ai.tool.name"] == "get_deployment_history"
        assert span.attributes["gen_ai.tool.type"] == "function"
        assert "gen_ai.tool.call.arguments" not in span.attributes
    assert "mcp.method.name" not in local_span.attributes
    assert remote_span.attributes["mcp.method.name"] == "tools/call"
    assert remote_span.attributes["server.address"] == "control-plane"
    assert remote_span.attributes["server.port"] == 8080
    assert remote_span.attributes["network.protocol.name"] == "http"


def test_kafka_consumer_and_producer_spans_bridge_the_trace():
    """소비 스팬(부모 = 헤더) 안에서 발행하면 발행 스팬이 그 자식이 되고, 동봉 헤더는 발행 스팬을 가리킨다.

    control-plane `{topic} send` → agent-service `{topic} process` → `{topic} send` → control-plane `{topic} process`
    사슬의 agent-service 몫을 고정한다 (Kafka 왕복 4스팬 중 가운데 2개).
    """
    from app.events.propagation import consumer_span, inject_headers
    from app.events.publishing import make_publisher

    exporter = _exporter()
    tracer = trace.get_tracer("test")
    with tracer.start_as_current_span("ops.incidents send", kind=trace.SpanKind.PRODUCER) as upstream:
        incoming = inject_headers()

    class _Producer:
        def __init__(self) -> None:
            self.headers: list = []

        async def send_and_wait(self, topic, key, value, headers=None):
            self.headers = headers or []

    producer = _Producer()
    publish = make_publisher(producer, "ops.analysis.results")

    async def handle():
        with consumer_span("ops.incidents", incoming):
            await publish("inc-otel-007", {"incident_id": "inc-otel-007"})

    asyncio.run(handle())

    spans = {s.name: s for s in exporter.get_finished_spans() if s.name in ("ops.incidents process", "ops.analysis.results send")}
    consume, send = spans["ops.incidents process"], spans["ops.analysis.results send"]
    assert consume.kind == trace.SpanKind.CONSUMER and consume.parent.span_id == upstream.get_span_context().span_id
    assert consume.attributes["messaging.destination.name"] == "ops.incidents"
    assert send.kind == trace.SpanKind.PRODUCER and send.parent.span_id == consume.context.span_id
    assert send.attributes["messaging.operation.type"] == "send"
    traceparent = dict((k, v.decode()) for k, v in producer.headers)["traceparent"]
    assert traceparent.split("-")[2] == format(send.context.span_id, "016x")  # 동봉 헤더 = 발행 스팬


def test_resource_identifies_service_instance():
    """Resource 에 service.instance.id — 복제본 구분 (없으면 Collector prometheus exporter 에서 복제본 메트릭이 겹친다)."""
    from opentelemetry.sdk.trace import TracerProvider

    from app.config.otel import build_resource

    setup_telemetry()
    provider = trace.get_tracer_provider()
    assert isinstance(provider, TracerProvider)
    attrs = provider.resource.attributes
    assert attrs["service.name"] == "agent-service"
    assert attrs["service.instance.id"]  # 비어 있지 않은 문자열 (HOSTNAME 또는 호스트명)
    assert build_resource().attributes["service.instance.id"] == attrs["service.instance.id"]


def test_workflow_span_carries_experiment_assignment(monkeypatch):
    """배정된 실험은 루트 스팬 속성 aiops.experiment.name/variant 로 — Tempo 에서 variant 별 trace 를 가른다 (ADR-0019)."""
    from app.experiments.assigner import ExperimentAssigner
    from app.experiments.definition import ExperimentDefinition, VariantSpec
    from app.supervisor import runtime as runtime_module

    definition = ExperimentDefinition(
        name="analysis-prompt-v2", target="analysis", variants={"A": VariantSpec(prompt="v1"), "B": VariantSpec(prompt="v2")}
    )
    monkeypatch.setattr(runtime_module, "experiment_assigner", lambda: ExperimentAssigner([definition]))
    exporter = _exporter()
    graph = _SpanCapturingGraph()

    asyncio.run(GraphRuntime(graph).start(build_incident("latency-surge", "inc-otel-exp")))

    span = next(s for s in exporter.get_finished_spans() if s.attributes.get("incident.id") == "inc-otel-exp")
    assert span.attributes["aiops.experiment.name"] == "analysis-prompt-v2"
    assert span.attributes["aiops.experiment.variant"] == graph.inputs[0]["experiment"].variant


def test_record_experiment_tags_current_span():
    from app.config.agent_spans import record_experiment
    from app.supervisor.state import ExperimentAssignment

    exporter = _exporter()
    tracer = trace.get_tracer("test")
    with tracer.start_as_current_span("invoke_agent analysis"):
        record_experiment(ExperimentAssignment(name="analysis-model-haiku", variant="B", model_override=True))
        record_experiment(None)  # 배정 없음은 무기록 — 속성 부재가 "실험 밖" 의 표현

    span = next(s for s in exporter.get_finished_spans() if s.name == "invoke_agent analysis")
    assert span.attributes["aiops.experiment.name"] == "analysis-model-haiku"
    assert span.attributes["aiops.experiment.variant"] == "B"


def test_resume_span_carries_experiment_from_checkpoint(monkeypatch):
    """재개 trace 도 같은 실험 좌표 — 승인 뒤 이어지는 실행이 Tempo 에서 variant 로 검색되게 (체크포인트의 배정을 읽는다)."""
    from app.experiments.assigner import ExperimentAssigner
    from app.experiments.definition import ExperimentDefinition, VariantSpec
    from app.supervisor import runtime as runtime_module

    definition = ExperimentDefinition(
        name="analysis-prompt-v2", target="analysis", variants={"A": VariantSpec(prompt="v1"), "B": VariantSpec(prompt="v2")}
    )
    monkeypatch.setattr(runtime_module, "experiment_assigner", lambda: ExperimentAssigner([definition]))
    exporter = _exporter()
    graph = _SpanCapturingGraph()
    runtime = GraphRuntime(graph)

    async def run() -> None:
        await runtime.start(build_incident("latency-surge", "inc-otel-exp-resume"))
        graph._state = dict(graph.inputs[0])  # 체크포인트 = 시작 입력 (스텁은 상태를 저장하지 않는다)
        await runtime.resume("inc-otel-exp-resume")

    asyncio.run(run())

    spans = [s for s in exporter.get_finished_spans() if s.attributes.get("incident.id") == "inc-otel-exp-resume"]
    resumed = next(s for s in spans if s.attributes["aiops.resumed"] is True)
    assert resumed.attributes["aiops.experiment.name"] == "analysis-prompt-v2"
    assert resumed.attributes["aiops.experiment.variant"] == graph.inputs[0]["experiment"].variant
