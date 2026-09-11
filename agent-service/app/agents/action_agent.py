"""실행 에이전트 — 분석 결과를 근거로 조치 계획(ActionPlan)을 생성한다.

계획 생성까지만 담당 — 실제 실행은 human-in-the-loop 승인 이후 (ADR-0005).
도구 없음: 조치 계획은 분석 보고서만으로 작성한다 (새 조회가 필요하면 그건 분석 몫).
화이트리스트는 이중 강제 — 프롬프트 지시 + ActionType Literal 스키마 검증.
"""

from functools import lru_cache

from langchain.agents import create_agent
from langchain_core.messages import AIMessage, HumanMessage

from app.config import get_settings
from app.config.agent_spans import record_prompt_version
from app.config.llm import create_llm
from app.prompts.registry import prompt_registry
from app.security.untrusted import UNTRUSTED_POLICY, wrap_untrusted
from app.supervisor.state import ActionPlan, AIOpsState

AGENT = "action"


def action_system_prompt(version: str | None = None) -> str:
    """버전 파일(조치 카탈로그 — ActionType 과 1:1·작성 지침) + 비신뢰 정책 절."""
    return prompt_registry().get(AGENT, version) + UNTRUSTED_POLICY



def get_action_agent():
    """실행 에이전트를 지연 생성한다 — import 시점에 LLM API 키를 요구하지 않기 위해. 캐시 키는 프롬프트 버전."""
    return _build_action_agent(prompt_registry().version_of(AGENT))


@lru_cache
def _build_action_agent(prompt_version: str):
    settings = get_settings()
    return create_agent(
        model=create_llm(settings, task_type="action-planning"),
        tools=[],
        system_prompt=action_system_prompt(prompt_version),
        response_format=ActionPlan,
    )


async def action_node(state: AIOpsState) -> dict:
    # async 인 이유: 노드 타임아웃은 협조적 취소(asyncio) 기반 — sync 노드는 지원되지 않는다 (DAY 13)
    incident = state["incident"]
    analysis = state["analysis"]
    task = HumanMessage(
        content=(
            f"인시던트 — 시나리오: {incident.scenario}, Alert: {incident.alert_name}, "
            f"심각도: {analysis.severity}, 확신도: {analysis.confidence}\n"
            f"원인 가설: {analysis.root_cause_hypothesis}\n"
            # 근거는 로그·도구 결과 인용이라 주입 문구가 그대로 옮겨질 수 있다 — 여기서도 격리
            f"근거:\n{wrap_untrusted('analysis-evidence', '; '.join(analysis.evidence) or '(없음)')}\n"
            f"분석 단계 제안: {'; '.join(analysis.suggested_actions) or '(없음)'}\n"
            "조치 계획을 작성하라."
        )
    )
    record_prompt_version(prompt_registry().version_of(AGENT))
    result = await get_action_agent().ainvoke({"messages": [task]})

    plan: ActionPlan = result["structured_response"]
    return {
        "action": plan,
        "messages": [
            AIMessage(content=f"[action] 계획: {plan.actions} — {plan.rationale}")
        ],
    }
