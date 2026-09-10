"""GenAI 스팬 보강 — 인시던트 상관 속성 상속 + 게이트웨이 판정 헤더 승격 (agent-service `otel_genai.py` 이식).

evaluation-service 의 스팬 트리: `ops.analysis.results process`(CONSUMER, 부모 = agent-service 발행 헤더) →
평가 스팬 → Judge 의 게이트웨이 호출. 소비 스팬에 세운 incident.id·gen_ai.conversation.id 를 하위 스팬이
상속받아 품질 판정이 인시던트 trace 축에 붙는다 (docs/quality-evaluation.md §6).
"""

from opentelemetry import trace
from opentelemetry.context import Context
from opentelemetry.sdk.trace import ReadableSpan, Span, SpanProcessor

CONVERSATION_ID = "gen_ai.conversation.id"  # 표준 세션 축 — incident id
INCIDENT_ID = "incident.id"  # 같은 값의 자체 네임스페이스 판 — Tempo 검색 축
INHERITED_ATTRIBUTES = (CONVERSATION_ID, INCIDENT_ID)

GEN_AI_OPERATION_NAME = "gen_ai.operation.name"
OPERATION_CHAT = "chat"
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
    """소비 스팬에 부여하는 상관 속성 — 하위 스팬은 GenAiSpanEnricher 가 상속시킨다."""
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
        """httpx 스팬의 부모가 열려 있는 gen_ai 스팬이면 그 스팬 — 아니면 None.

        기록 중이 아닌 스팬(NonRecordingSpan)은 `parent` 가 없다 — 훅 예외는 httpx 계측이 전파해 호출 자체를
        실패시키므로(agent-service 2026-09-08 실측) 조용히 건너뛴다.
        """
        if not span.is_recording():
            return None
        parent = getattr(span, "parent", None)
        return self._live_genai_spans.get(parent.span_id) if parent is not None else None


def build_gateway_header_hooks(enricher: GenAiSpanEnricher):
    """httpx 계측의 response_hook 쌍(동기·비동기) — 게이트웨이 판정 헤더를 부모 `chat` 스팬 속성으로 승격.

    그 밖의 호출(Prometheus·Loki·토큰 발급)은 부모가 등록부에 없어 아무 일도 하지 않는다.
    """

    def promote(span, request, response) -> None:
        target = enricher.genai_parent_of(span)
        if target is None or (target.attributes or {}).get(GEN_AI_OPERATION_NAME) != OPERATION_CHAT:
            return
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

    async def promote_async(span, request, response) -> None:
        promote(span, request, response)

    return promote, promote_async
