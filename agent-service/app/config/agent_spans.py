"""Agent Spans (DAY 43) — 워크플로 루트·노드·도구를 GenAI 컨벤션 스팬으로 감싼다 (docs/otel-genai-mapping.md §3·§4).

계층: invoke_workflow incident-response ▸ invoke_agent {노드} ▸ execute_tool {도구} ▸ (httpx CLIENT). LLM 호출 스팬
(`chat`)은 openai-v2 계측기 몫이라 여기 없다. 세션 축(gen_ai.conversation.id·incident.id)은 루트에만 두고
GenAiSpanEnricher 가 하위로 상속시킨다.

노드·도구는 util-genai 핸들러(`invoke_local_agent`·`tool`)를 쓰고, 루트만 raw tracer 다 — 핸들러의 `workflow()` 는
span link(승인 전후 연결)와 시작 시점 사용자 속성(상속 원천)을 받지 못한다.
"""

import inspect
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from functools import wraps
from typing import Any
from urllib.parse import urlparse

from langchain_core.tools import BaseTool, StructuredTool
from opentelemetry import trace
from opentelemetry.context import Context
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.trace import Link, Span, SpanContext, SpanKind, TraceFlags
from opentelemetry.util.genai.handler import TelemetryHandler

from app.config.otel_genai import GEN_AI_OPERATION_NAME, incident_attributes

WORKFLOW_NAME = "incident-response"
AIOPS_NODE = "aiops.node"  # 우리 확장 — LangGraph 노드 이름 (mapping §5)
MCP_METHOD_NAME = "mcp.method.name"
TOOLS_CALL = "tools/call"

_tracer = trace.get_tracer("agent-service")
_handler: TelemetryHandler | None = None


def telemetry_handler() -> TelemetryHandler:
    """util-genai 핸들러 — SDK provider 가 등록된 뒤 첫 사용 시점에 만든다.

    핸들러는 생성 시점의 전역 provider 로 tracer 를 잡으므로 setup_telemetry 보다 먼저 만들면 no-op 이 굳는다.
    SDK provider 가 아직 없으면(단위 테스트 일부) 캐시하지 않고 매번 새로 — 나중에 provider 가 생기면 그때 캐시.
    """
    global _handler
    if _handler is not None:
        return _handler
    handler = TelemetryHandler()
    if isinstance(trace.get_tracer_provider(), TracerProvider):
        _handler = handler
    return handler


RunSpanRef = tuple[str, str]  # (trace_id hex, span_id hex) — 체크포인트에 보관하는 run 스팬 좌표


def _link_to(ref: RunSpanRef | None) -> list[Link]:
    if ref is None:
        return []
    trace_id, span_id = ref
    return [
        Link(
            SpanContext(int(trace_id, 16), int(span_id, 16), is_remote=True, trace_flags=TraceFlags(TraceFlags.SAMPLED)),
            attributes={"aiops.link.reason": "resume-after-approval"},
        )
    ]


@contextmanager
def workflow_span(
    incident_id: str,
    *,
    parent_context: Context | None = None,
    link_to: RunSpanRef | None = None,
    resumed: bool = False,
) -> Iterator[Span]:
    """인시던트 실행 1건의 루트 — `invoke_workflow incident-response` (INTERNAL).

    parent_context: Kafka 헤더에서 복원한 상류(control-plane 발행) 컨텍스트 — 있으면 그 trace 에 잇는다.
    link_to: 승인 대기로 끊긴 원 실행(run)의 좌표 — 재개 trace 가 원 trace 를 가리키는 link.
    """
    attributes: dict[str, Any] = {
        GEN_AI_OPERATION_NAME: "invoke_workflow",
        "gen_ai.workflow.name": WORKFLOW_NAME,
        "aiops.resumed": resumed,
        **incident_attributes(incident_id),
    }
    with _tracer.start_as_current_span(
        f"invoke_workflow {WORKFLOW_NAME}",
        context=parent_context,
        kind=SpanKind.INTERNAL,
        attributes=attributes,
        links=_link_to(link_to),
    ) as span:
        yield span


def span_ref(span: Span) -> RunSpanRef:
    ctx = span.get_span_context()
    return format(ctx.trace_id, "032x"), format(ctx.span_id, "016x")


def agent_node(name: str, node: Callable[..., Any]) -> Callable[..., Any]:
    """LangGraph 노드를 `invoke_agent {name}` 스팬으로 감싼다 — 동기/비동기 노드 모두 같은 종류로 유지.

    노드 본문은 무수정: 래퍼는 상태를 읽지 않고 이름만 안다. 노드 안의 LLM 호출·도구 호출이 이 스팬의 자식이 된다.
    """
    if inspect.iscoroutinefunction(node):

        @wraps(node)
        async def async_wrapped(*args: Any, **kwargs: Any) -> Any:
            with telemetry_handler().invoke_local_agent(agent_name=name) as invocation:
                invocation.attributes[AIOPS_NODE] = name
                return await node(*args, **kwargs)

        return async_wrapped

    @wraps(node)
    def wrapped(*args: Any, **kwargs: Any) -> Any:
        with telemetry_handler().invoke_local_agent(agent_name=name) as invocation:
            invocation.attributes[AIOPS_NODE] = name
            return node(*args, **kwargs)

    return wrapped


def plain_node(name: str, node: Callable[..., Any]) -> Callable[..., Any]:
    """LLM 없는 노드(approval·recovery)는 일반 INTERNAL 스팬 `{name}` + aiops.node.

    approval 은 interrupt 를 예외로 전파해 정지하므로 예외를 오류로 기록하지 않는다 (승인 대기는 정상 경로).
    """
    if inspect.iscoroutinefunction(node):

        @wraps(node)
        async def async_wrapped(*args: Any, **kwargs: Any) -> Any:
            with _tracer.start_as_current_span(
                name, attributes={AIOPS_NODE: name}, record_exception=False, set_status_on_exception=False
            ):
                return await node(*args, **kwargs)

        return async_wrapped

    @wraps(node)
    def wrapped(*args: Any, **kwargs: Any) -> Any:
        with _tracer.start_as_current_span(
            name, attributes={AIOPS_NODE: name}, record_exception=False, set_status_on_exception=False
        ):
            return node(*args, **kwargs)

    return wrapped


def _mcp_attributes(server_url: str) -> dict[str, Any]:
    parsed = urlparse(server_url)
    attributes: dict[str, Any] = {MCP_METHOD_NAME: TOOLS_CALL, "network.protocol.name": "http"}
    if parsed.hostname:
        attributes["server.address"] = parsed.hostname
    if parsed.port:
        attributes["server.port"] = parsed.port
    return attributes


def instrumented_tool(tool: BaseTool, *, mcp_server_url: str | None = None) -> BaseTool:
    """도구 호출을 `execute_tool {도구}` 스팬으로 감싼 사본 — 이름·설명·스키마는 그대로 (untrusted_tool 과 같은 방식).

    mcp_server_url 이 있으면 MCP 클라이언트 속성(mcp.method.name=tools/call·server.*)을 더한다 — 규격대로 별도
    MCP 스팬을 만들지 않고 이 도구 스팬에 싣는다. 세션·프로토콜 버전은 httpx 훅이 헤더에서 올린다 (otel_genai).
    인자·결과는 캡처 정책이 SPAN_ONLY 일 때만 (마스킹 전 원문 — mapping §6).
    """
    if not isinstance(tool, StructuredTool):
        raise TypeError(f"StructuredTool 만 감쌀 수 있다: {type(tool).__name__}")
    extra = _mcp_attributes(mcp_server_url) if mcp_server_url else {}

    def open_invocation(kwargs: dict[str, Any]):
        invocation = telemetry_handler().tool(tool.name, tool_type="function", tool_description=tool.description)
        invocation.attributes.update(extra)
        if invocation.should_capture_content_on_span:
            invocation.arguments = kwargs
        return invocation

    updates: dict[str, Any] = {}
    if tool.coroutine is not None:
        original_coroutine = tool.coroutine

        async def coroutine(*args: Any, **kwargs: Any) -> Any:
            with open_invocation(kwargs) as invocation:
                result = await original_coroutine(*args, **kwargs)
                if invocation.should_capture_content_on_span:
                    invocation.tool_result = _text_of(result)
                return result

        updates["coroutine"] = coroutine
    if tool.func is not None:
        original_func = tool.func

        def func(*args: Any, **kwargs: Any) -> Any:
            with open_invocation(kwargs) as invocation:
                result = original_func(*args, **kwargs)
                if invocation.should_capture_content_on_span:
                    invocation.tool_result = _text_of(result)
                return result

        updates["func"] = func
    return tool.model_copy(update=updates)


def _text_of(result: Any) -> Any:
    # content_and_artifact 도구는 (content, artifact) — 스팬에는 content 만. 그 외는 문자열화 가능 값 그대로
    if isinstance(result, tuple) and len(result) == 2:
        return result[0]
    return result if isinstance(result, (str, int, float, bool)) else str(result)
