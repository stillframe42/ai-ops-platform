"""분석 에이전트 — 모니터링 요약을 입력으로 근본 원인 가설을 세우고 도구로 검증한다.

출력은 response_format=AnalysisResult 구조화 — 자유 텍스트 파싱 없이 상태에 그대로 담는다.
severity 는 Supervisor 라우팅(P3 조기 종료)의 입력이 된다.

도구 구성 (DAY 16): 관측 스택 직접 조회 2종은 로컬(ADR-0002), 운영 도구(배포 이력·
유사 인시던트·앱 설정)는 control-plane MCP 서버에서 발견한다 (ADR-0010).
"""

import logging

from langchain.agents import create_agent
from langchain_core.messages import AIMessage, HumanMessage

from app.config import get_settings
from app.config.llm import create_llm
from app.supervisor.state import AIOpsState, AnalysisResult
from app.tools.loki_tools import get_app_logs
from app.tools.mcp_tools import load_mcp_tools
from app.tools.prometheus_tools import compare_with_baseline

logger = logging.getLogger(__name__)

# ReAct 무한 루프 방지 — LLM+tool 왕복 1회당 스텝 2 이므로 도구 호출 약 7회 상한 (5월 패턴)
ANALYSIS_RECURSION_LIMIT = 16

ANALYSIS_SYSTEM_PROMPT = """\
너는 AIOps 플랫폼의 분석 에이전트다. 모니터링 에이전트의 상황 요약을 입력받아
근본 원인 가설을 세우고, 도구로 검증한 뒤 원인 보고서를 작성한다.

분석 절차:
1. 상황 요약에서 장애 유형을 파악하고 근본 원인 가설을 세운다.
2. 도구로 가설을 검증한다 — 가설과 배치되는 근거가 나오면 가설을 수정하라.
   - get_app_logs: 에러의 실제 원인은 로그에 있다 (5xx 면 ERROR 로그부터 확인)
   - compare_with_baseline: 현재 vs 1시간 전 비교 — 배경 부하(k6 상시 2 RPS)가 일정해
     1시간 전이 평상시 기준선이다 (예: GC 빈도 증가는
     compare_with_baseline('sum(rate(jvm_gc_pause_seconds_count[5m]))') 로 확인)
   - 그 외 운영 도구는 관제 시스템(ops-control-plane)이 제공한다 — 각 도구의 설명을
     참고해 활용하라. 특히 배포 이력으로 최근 배포와 장애 시점의 상관을 확인/배제하고,
     유사 인시던트 검색으로 과거의 원인·조치를 참고하라.
3. 검증에 사용한 근거를 evidence 에 수치·로그 내용과 함께 남긴다.

severity 기준:
- P1: 서비스 전면 장애 수준 (대부분의 요청 실패 또는 불능)
- P2: 부분 영향 (일부 엔드포인트 저하, 트리거 스펙 임계 초과 지속)
- P3: 사용자 영향 미미 (관찰만 필요, 조치 불요)

confidence 는 근거의 강도에 따라 0~1 로 정직하게 매겨라 — 근거가 정황뿐이면 낮게.
suggested_actions 는 구체적 조치 후보를 짧게 나열한다 (실행 여부는 다음 단계 몫).

환경 특성 — 조치 판단 기준 (중요): 이 플랫폼의 장애는 데모용 fault-injection 으로
재현될 수 있다. 주입 흔적을 발견하면 원인 규명에는 그 사실을 기록하되, severity 와
suggested_actions 는 "같은 증상이 실제 운영에서 발생했다면"을 기준으로 판단하라.
"인위적 주입이므로 관찰만으로 충분"이라는 결론은 금지 — 주입은 실제 장애의 대역이며,
증상을 해소할 조치 후보(재시작·스케일아웃·롤백 등)를 실제 장애와 동일하게 제안해야 한다.
"""

# 관측 스택 직접 조회 도구 — MCP 대상 아님 (ADR-0002 경계)
LOCAL_ANALYSIS_TOOLS = [get_app_logs, compare_with_baseline]

# lru_cache 대신 수동 캐시 — "성공 시에만 캐시"라는 조건부 정책이 필요해서
_cached_agent = None


async def get_analysis_agent():
    """분석 에이전트를 지연 생성한다 — import 시점에 LLM API 키를 요구하지 않기 위해.
    MCP 도구 발견(tools/list 1왕복)을 포함하므로 async 다.

    캐시 정책: 발견 성공 시에만 캐시한다. 실패하면 로컬 도구만으로 강등해 이번 실행은
    부분 진행하고(DAY 13 관례 — 공백은 프롬프트가 아니라 도구 부재로 드러난다), 캐시하지
    않으므로 다음 실행에서 발견을 재시도한다 — MCP 서버 복구가 재기동 없이 반영된다.
    """
    global _cached_agent
    if _cached_agent is not None:
        return _cached_agent

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
        model=create_llm(settings),
        tools=LOCAL_ANALYSIS_TOOLS + mcp_tools,
        system_prompt=ANALYSIS_SYSTEM_PROMPT,
        response_format=AnalysisResult,
    )
    if discovered:
        _cached_agent = agent
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
            f"모니터링 상황 요약: {summary}\n"
            "근본 원인 가설을 세우고 도구로 검증해 원인 보고서를 작성하라."
        )
    )
    agent = await get_analysis_agent()
    result = await agent.ainvoke(
        {"messages": [task]},
        config={"recursion_limit": ANALYSIS_RECURSION_LIMIT},
    )

    analysis: AnalysisResult = result["structured_response"]
    return {
        "analysis": analysis,
        "messages": [
            AIMessage(content=f"[analysis] ({analysis.severity}) {analysis.root_cause_hypothesis}")
        ],
    }
