"""GenAI 스팬 보강 — 인시던트 상관 속성 상속 + 게이트웨이 판정 헤더 승격 (DAY 43).

둘 다 SpanProcessor·httpx 훅으로 구현하는 이유: openai-v2 계측기에는 응답 훅 같은 확장 지점이 없고,
LangGraph 노드 코드가 스팬을 직접 만지지 않게 하기 위해서다 (docs/otel-genai-mapping.md §5).

동작 전제 (util-genai 1.1b0 실측): `chat` 스팬은 start_span 뒤 컨텍스트에 attach 된 채 openai SDK 를 호출하므로,
SDK 내부 httpx 스팬의 부모가 곧 `chat` 스팬이고 응답 훅 시점에 `chat` 스팬은 아직 열려 있다.
"""

from opentelemetry import trace
from opentelemetry.context import Context
from opentelemetry.sdk.trace import ReadableSpan, Span, SpanProcessor

CONVERSATION_ID = "gen_ai.conversation.id"  # 표준 세션 축 — Langfuse 세션도 이 값을 읽는다
INCIDENT_ID = "incident.id"  # 같은 값의 자체 네임스페이스 판 — Tempo 검색 축
INHERITED_ATTRIBUTES = (CONVERSATION_ID, INCIDENT_ID)

GEN_AI_OPERATION_NAME = "gen_ai.operation.name"  # 계측기가 스팬 시작 시점에 세우는 식별 속성
OPERATION_CHAT = "chat"
OPERATION_EXECUTE_TOOL = "execute_tool"
# MCP 클라이언트 속성 (mcp.md) — 어댑터가 도구 호출마다 여는 세션의 헤더에서 올린다
MCP_SESSION_ID_HEADER = "mcp-session-id"  # 응답 헤더 (initialize 응답부터)
MCP_PROTOCOL_VERSION_HEADER = "mcp-protocol-version"  # 요청 헤더 (initialize 이후 요청)
MCP_SESSION_ID = "mcp.session.id"
MCP_PROTOCOL_VERSION = "mcp.protocol.version"
GATEWAY_TASK_TYPE_HEADER = "x-task-type"
GATEWAY_TASK_TYPE = "gateway.task_type"
# 게이트웨이 판정 응답 헤더 → 클라이언트 스팬 속성. 헤더명은 llm-gateway 가 쓰는 5종과 일치해야 한다
GATEWAY_RESPONSE_HEADERS = {
    "x-gateway-cache": "gateway.cache",
    "x-gateway-guardrail": "gateway.guardrail",
    "x-gateway-guardrail-stage": "gateway.guardrail_stage",
    "x-gateway-downgrade": "gateway.downgrade",
    "x-gateway-fallback": "gateway.fallback",
}


def incident_attributes(incident_id: str) -> dict[str, str]:
    """루트 스팬(incident.run/resume)에 부여하는 상관 속성 — 하위 스팬은 GenAiSpanEnricher 가 상속시킨다."""
    return {CONVERSATION_ID: incident_id, INCIDENT_ID: incident_id}


class GenAiSpanEnricher(SpanProcessor):
    """① 부모의 인시던트 상관 속성을 자식 스팬에 복사 ② 열려 있는 gen_ai 스팬을 등록해 httpx 훅이 찾게 한다."""

    def __init__(self) -> None:
        self._live_genai_spans: dict[int, Span] = {}

    def on_start(self, span: Span, parent_context: Context | None = None) -> None:
        parent = trace.get_current_span(parent_context)
        # 원격 전파 부모(NonRecordingSpan)는 속성이 없다 — SDK 스팬만 상속 원천
        if isinstance(parent, ReadableSpan) and parent.attributes:
            for key in INHERITED_ATTRIBUTES:
                value = parent.attributes.get(key)
                if value is not None and (span.attributes or {}).get(key) is None:
                    span.set_attribute(key, value)
        if span.attributes and GEN_AI_OPERATION_NAME in span.attributes:
            self._live_genai_spans[span.context.span_id] = span

    def on_end(self, span: ReadableSpan) -> None:
        self._live_genai_spans.pop(span.context.span_id, None)

    def genai_parent_of(self, span: Span) -> Span | None:
        """httpx 스팬의 부모가 열려 있는 gen_ai 스팬이면 그 스팬 — 아니면 None (게이트웨이 외 호출).

        샘플링에서 빠졌거나 SDK 가 꺼진 요청의 스팬은 `NonRecordingSpan` 이라 `parent` 가 없다 — 훅에서 예외가 나면
        httpx 계측이 그대로 전파해 **LLM 호출 자체가 실패**하므로(2026-09-08 실측) 기록 중이 아니면 조용히 건너뛴다.
        """
        if not span.is_recording():
            return None
        parent = getattr(span, "parent", None)
        return self._live_genai_spans.get(parent.span_id) if parent is not None else None


def build_gateway_header_hooks(enricher: GenAiSpanEnricher):
    """httpx 계측의 response_hook 쌍(동기·비동기) — 응답 헤더를 부모 gen_ai 스팬 속성으로 승격.

    부모가 `chat` 이면 게이트웨이 판정 헤더(gateway.*), `execute_tool` 이면 MCP 세션·프로토콜 헤더(mcp.*).
    그 밖의 호출(Prometheus·Loki·토큰 발급)은 부모가 등록부에 없어 아무 일도 하지 않는다.
    """

    def promote(span, request, response) -> None:
        target = enricher.genai_parent_of(span)
        if target is None:
            return
        operation = (target.attributes or {}).get(GEN_AI_OPERATION_NAME)
        if operation == OPERATION_CHAT:
            _promote_gateway(target, request, response)
        elif operation == OPERATION_EXECUTE_TOOL:
            _promote_mcp(target, request, response)

    async def promote_async(span, request, response) -> None:
        promote(span, request, response)

    return promote, promote_async


def _promote_gateway(target, request, response) -> None:
    if request.headers is not None:
        task_type = request.headers.get(GATEWAY_TASK_TYPE_HEADER)
        if task_type:
            target.set_attribute(GATEWAY_TASK_TYPE, task_type)
    if response.headers is None:
        return
    for header, attribute in GATEWAY_RESPONSE_HEADERS.items():
        value = response.headers.get(header)
        if value is not None:
            target.set_attribute(attribute, value)


def _promote_mcp(target, request, response) -> None:
    # 세션 1개 = 요청 여러 건(initialize → notifications/initialized → tools/call) — 같은 값이라 마지막 덮어쓰기 무해
    if request.headers is not None:
        version = request.headers.get(MCP_PROTOCOL_VERSION_HEADER)
        if version:
            target.set_attribute(MCP_PROTOCOL_VERSION, version)
    if response.headers is not None:
        session_id = response.headers.get(MCP_SESSION_ID_HEADER)
        if session_id:
            target.set_attribute(MCP_SESSION_ID, session_id)
