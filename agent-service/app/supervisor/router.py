"""모호 구간 LLM 라우터 — 규칙으로 가를 수 없는 전이만 LLM 이 판단한다 (ADR-0008).

적용 구간은 단 하나: 분석이 P1·P2 인데 confidence 가 낮을 때, 그 보고서로 조치 계획을
만들지(action) 재분석할지(analysis). 명확한 전이는 supervisor_node 의 규칙이 담당한다.
"""

from functools import lru_cache
from typing import Literal

from pydantic import BaseModel

from app.config import get_settings
from app.config.llm import create_llm

_ROUTE_PROMPT = """\
너는 AIOps 플랫폼의 Supervisor 라우터다. 분석 에이전트가 낮은 확신의 보고서를 냈다.
다음 중 하나를 결정하라:
- analysis: 근거가 부족하거나 가설이 흔들린다 — 재분석으로 근거를 보강할 가치가 있다.
- action: 확신은 낮아도 방향이 맞다 — 조치 계획 수립(실행 아님, 승인 전 제안)으로 진행한다.

재분석은 LLM 비용이 들고 인시던트 대응을 지연시킨다 — 재분석이 새 근거를 얻을 가능성이
낮으면 action 을 골라라. reason 에 판단 근거를 한두 문장으로 남겨라.

분석 보고서:
- 가설: {hypothesis}
- 심각도: {severity} / 확신도: {confidence}
- 근거: {evidence}
"""


class RouteDecision(BaseModel):
    """LLM 라우팅 판단 — 다음 노드와 그 근거 (messages 에 남겨 추적 가능하게)."""

    next: Literal["analysis", "action"]
    reason: str


@lru_cache
def get_route_llm():
    """라우팅 판단용 구조화 출력 LLM — 도구가 없는 단발 판단이라 create_agent 불필요."""
    settings = get_settings()
    # method="function_calling" 고정 — 게이트웨이 도구 passthrough 경로 사용 (json_schema 는 게이트웨이 미지원)
    return create_llm(settings, task_type="routing-decision").with_structured_output(
        RouteDecision, method="function_calling"
    )


def decide_ambiguous_route(analysis) -> RouteDecision:
    prompt = _ROUTE_PROMPT.format(
        hypothesis=analysis.root_cause_hypothesis,
        severity=analysis.severity,
        confidence=analysis.confidence,
        evidence="; ".join(analysis.evidence) or "(없음)",
    )
    return get_route_llm().invoke(prompt)
