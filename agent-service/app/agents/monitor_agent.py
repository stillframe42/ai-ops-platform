"""모니터링 에이전트 — Alert 수신 시 관련 메트릭을 수집해 상황 요약을 생성한다.

LLM 이 스스로 PromQL 을 작성해 조회한다 (create_agent + prometheus_tools).
원인 분석은 분석 에이전트 몫 — 여기서는 관측된 사실 요약까지만.
"""

import json
from functools import lru_cache

from langchain.agents import create_agent
from langchain_core.messages import AIMessage, AnyMessage, HumanMessage

from app.agents.tool_errors import ToolErrorFeedback
from app.config import get_settings
from app.config.agent_spans import instrumented_tool, record_prompt_version
from app.config.llm import create_llm
from app.prompts.registry import prompt_registry
from app.security.untrusted import UNTRUSTED_POLICY, wrap_untrusted
from app.supervisor.state import AIOpsState, MonitoringResult
from app.tools.prometheus_tools import (
    get_active_alerts,
    query_prometheus,
    query_prometheus_range,
)

AGENT = "monitor"


def monitor_system_prompt(version: str | None = None) -> str:
    """버전 파일(메트릭 이름·트리거 스펙·조회 지침) + 비신뢰 정책 절."""
    return prompt_registry().get(AGENT, version) + UNTRUSTED_POLICY


MONITOR_TOOLS = [get_active_alerts, query_prometheus, query_prometheus_range]


def get_monitor_agent():
    """모니터링 에이전트를 지연 생성한다 — import 시점에 LLM API 키를 요구하지 않기 위해. 캐시 키는 프롬프트 버전."""
    return _build_monitor_agent(prompt_registry().version_of(AGENT))


@lru_cache
def _build_monitor_agent(prompt_version: str):
    settings = get_settings()
    return create_agent(
        model=create_llm(settings, task_type="monitoring-summary"),
        tools=[instrumented_tool(tool) for tool in MONITOR_TOOLS],  # execute_tool 스팬 (DAY 43)
        system_prompt=monitor_system_prompt(prompt_version),
        # 비일시적 도구 오류(화이트리스트 거부·4xx)는 모델 피드백으로 — 노드 실패 대신 재시도 기회 (DAY 46)
        middleware=[ToolErrorFeedback()],
    )


def _extract_evidences(messages: list[AnyMessage]) -> list[str]:
    """에이전트가 실행한 tool 호출을 근거 목록으로 남긴다 (어떤 쿼리로 판단했는지 추적용)."""
    evidences = []
    for message in messages:
        for call in getattr(message, "tool_calls", None) or []:
            evidences.append(f"{call['name']}({json.dumps(call['args'], ensure_ascii=False)})")
    return evidences


async def monitor_node(state: AIOpsState) -> dict:
    # async 인 이유: 노드 타임아웃은 협조적 취소(asyncio) 기반 — sync 노드는 지원되지 않는다 (DAY 13)
    incident = state["incident"]
    task = HumanMessage(
        content=(
            f"인시던트 발생 — 시나리오: {incident.scenario}, Alert: {incident.alert_name}, "
            # summary 는 웹훅 annotation 원문 — 비신뢰 (위협 모델 ②)
            f"발생 시각: {incident.occurred_at}\n요약:\n{wrap_untrusted('alert-annotation', incident.summary)}\n"
            "관련 메트릭을 조회해 현재 상황을 요약하라."
        )
    )
    record_prompt_version(prompt_registry().version_of(AGENT))
    result = await get_monitor_agent().ainvoke({"messages": [task]})

    # content 는 콘텐츠 블록 리스트일 수 있다 (thinking 블록 포함 시) — .text 로 텍스트만 추출
    summary = result["messages"][-1].text
    monitoring = MonitoringResult(
        situation_summary=summary,
        evidences=_extract_evidences(result["messages"]),
    )
    return {
        "monitoring": monitoring,
        "messages": [AIMessage(content=f"[monitor] {summary}")],
    }
