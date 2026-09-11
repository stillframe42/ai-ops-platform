"""모호 구간 LLM 라우터 — 규칙으로 가를 수 없는 전이만 LLM 이 판단한다 (ADR-0008).

적용 구간은 단 하나: 분석이 P1·P2 인데 confidence 가 낮을 때, 그 보고서로 조치 계획을
만들지(action) 재분석할지(analysis). 명확한 전이는 supervisor_node 의 규칙이 담당한다.
"""

from functools import lru_cache
from typing import Literal

from pydantic import BaseModel

from app.config import get_settings
from app.config.agent_spans import record_prompt_version
from app.config.llm import create_llm
from app.prompts.registry import prompt_registry
from app.security.untrusted import UNTRUSTED_POLICY, wrap_untrusted

AGENT = "router"


def route_prompt(version: str | None = None) -> str:
    """버전 파일(판단 기준 + {hypothesis}·{severity}·{confidence}·{evidence} 자리) + 비신뢰 정책 절."""
    return prompt_registry().get(AGENT, version) + UNTRUSTED_POLICY



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
    record_prompt_version(prompt_registry().version_of(AGENT))
    prompt = route_prompt().format(
        hypothesis=analysis.root_cause_hypothesis,
        severity=analysis.severity,
        confidence=analysis.confidence,
        evidence=wrap_untrusted("analysis-evidence", "; ".join(analysis.evidence) or "(없음)"),
    )
    return get_route_llm().invoke(prompt)
