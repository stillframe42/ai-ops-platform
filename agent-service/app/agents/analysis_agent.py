"""분석 에이전트 — 모니터링 요약을 입력으로 근본 원인 가설을 세우고 도구로 검증한다.

출력은 response_format=AnalysisResult 구조화 — 자유 텍스트 파싱 없이 상태에 그대로 담는다.
severity 는 Supervisor 라우팅(P3 조기 종료)의 입력이 된다.

도구 구성 (DAY 16): 관측 스택 직접 조회 2종은 로컬(ADR-0002), 운영 도구(배포 이력·
유사 인시던트·앱 설정)는 control-plane MCP 서버에서 발견한다 (ADR-0010).
"""

import logging

from langchain.agents import create_agent
from langchain_core.messages import AIMessage, HumanMessage

from app.agents.tool_errors import ToolErrorFeedback
from app.config import get_settings
from app.config.agent_spans import instrumented_tool, record_prompt_version
from app.config.llm import create_llm
from app.prompts.registry import prompt_registry
from app.security.untrusted import UNTRUSTED_POLICY, wrap_untrusted
from app.supervisor.state import AIOpsState, AnalysisResult
from app.tools.loki_tools import get_app_logs
from app.tools.mcp_tools import load_mcp_tools
from app.tools.prometheus_tools import compare_with_baseline

logger = logging.getLogger(__name__)

# ReAct 무한 루프 방지 — LLM+tool 왕복 1회당 스텝 2 이므로 도구 호출 약 7회 상한 (직전 프로젝트 검증 패턴)
ANALYSIS_RECURSION_LIMIT = 16

AGENT = "analysis"


def analysis_system_prompt(version: str | None = None) -> str:
    """버전 파일(분석 절차·severity 기준·환경 특성) + 비신뢰 정책 절."""
    return prompt_registry().get(AGENT, version) + UNTRUSTED_POLICY


# 관측 스택 직접 조회 도구 — MCP 대상 아님 (ADR-0002 경계)
LOCAL_ANALYSIS_TOOLS = [get_app_logs, compare_with_baseline]

# lru_cache 대신 수동 캐시(프롬프트 버전별) — "성공 시에만 캐시"라는 조건부 정책이 필요해서
_cached_agents: dict[str, object] = {}


async def get_analysis_agent():
    """분석 에이전트를 지연 생성한다 — import 시점에 LLM API 키를 요구하지 않기 위해.
    MCP 도구 발견(tools/list 1왕복)을 포함하므로 async 다. 캐시 키는 프롬프트 버전.

    캐시 정책: 발견 성공 시에만 캐시한다. 실패하면 로컬 도구만으로 강등해 이번 실행은
    부분 진행하고(DAY 13 관례 — 공백은 프롬프트가 아니라 도구 부재로 드러난다), 캐시하지
    않으므로 다음 실행에서 발견을 재시도한다 — MCP 서버 복구가 재기동 없이 반영된다.
    """
    prompt_version = prompt_registry().version_of(AGENT)
    cached = _cached_agents.get(prompt_version)
    if cached is not None:
        return cached

    settings = get_settings()
    try:
        mcp_tools = await load_mcp_tools(settings)
        discovered = True
    except Exception:
        logger.warning(
            "MCP 도구 발견 실패 (%s) — 로컬 도구만으로 진행, 다음 실행에서 재시도",
            settings.mcp_server_url,
            exc_info=True,
        )
        mcp_tools, discovered = [], False

    agent = create_agent(
        model=create_llm(settings, task_type="root-cause-analysis"),
        # 로컬 도구는 여기서, MCP 도구는 발견 시점(load_mcp_tools)에 execute_tool 스팬으로 감싼다 (DAY 43)
        tools=[instrumented_tool(tool) for tool in LOCAL_ANALYSIS_TOOLS] + mcp_tools,
        system_prompt=analysis_system_prompt(prompt_version),
        response_format=AnalysisResult,
        # 비일시적 도구 오류(화이트리스트 거부·4xx)는 모델 피드백으로 — 노드 실패 대신 재시도 기회 (DAY 46)
        middleware=[ToolErrorFeedback()],
    )
    if discovered:
        _cached_agents[prompt_version] = agent
    return agent


async def analysis_node(state: AIOpsState) -> dict:
    # async 인 이유: 노드 타임아웃은 협조적 취소(asyncio) 기반 — sync 노드는 지원되지 않는다 (DAY 13)
    incident = state["incident"]
    monitoring = state.get("monitoring")
    # monitor 실패 시에도 부분 진행한다 — 데이터 공백을 숨기지 않고 프롬프트에 명시 (DAY 13)
    summary = (
        monitoring.situation_summary
        if monitoring is not None
        else "(모니터링 단계 실패 — 상황 요약 없음. 도구로 직접 조회해 공백을 보완하라)"
    )
    task = HumanMessage(
        content=(
            f"인시던트 — 시나리오: {incident.scenario}, Alert: {incident.alert_name}, "
            f"발생 시각: {incident.occurred_at}\n"
            f"모니터링 상황 요약:\n{wrap_untrusted('monitor-summary', summary)}\n"
            "근본 원인 가설을 세우고 도구로 검증해 원인 보고서를 작성하라."
        )
    )
    prompt_version = prompt_registry().version_of(AGENT)
    record_prompt_version(prompt_version)
    agent = await get_analysis_agent()
    result = await agent.ainvoke(
        {"messages": [task]},
        config={"recursion_limit": ANALYSIS_RECURSION_LIMIT},
    )

    analysis: AnalysisResult = result["structured_response"]
    return {
        "analysis": analysis,
        # 보고서 페이로드 `analysis.prompt_version` 의 원천 — 평가·실험이 이 값으로 프롬프트 버전을 가른다 (ADR-0019)
        "analysis_prompt_version": prompt_version,
        "messages": [
            AIMessage(content=f"[analysis] ({analysis.severity}) {analysis.root_cause_hypothesis}")
        ],
    }
