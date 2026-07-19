"""Supervisor 오케스트레이션 그래프.

개별 에이전트는 create_agent 로 간결하게, 오케스트레이션은 StateGraph 로 명시적으로 구성한다.
라우팅은 하이브리드 (ADR-0008): 명확한 전이는 규칙, 모호한 판단(낮은 confidence)만 LLM.
"""

from langchain_core.messages import AIMessage
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph

from app.agents.action_agent import action_node
from app.agents.analysis_agent import analysis_node
from app.agents.monitor_agent import monitor_node
from app.supervisor import router
from app.supervisor.state import AIOpsState

# Supervisor 라우팅 결정값
MONITOR = "monitor"
ANALYSIS = "analysis"
ACTION = "action"
DONE = "done"

# 무한 루프 방지 — LLM 라우터가 재분석을 반복해도 이 한도에서 끊는다.
# 정상 흐름은 4회 (monitor/analysis/action 진입 3 + 종료 판정 1), 재분석 1회까지 허용.
MAX_SUPERVISOR_VISITS = 5

# 이 미만의 confidence 는 "조치로 갈지 재분석할지" 규칙으로 못 가름 — LLM 위임 구간
CONFIDENCE_THRESHOLD = 0.6

# 그래프 자체 스텝 상한 — 방문 카운터의 이중 방어 (invoke 시 config 로 전달)
GRAPH_RECURSION_LIMIT = 25


def supervisor_node(state: AIOpsState) -> dict:
    """상태를 보고 다음 에이전트를 결정한다 — 명확한 전이는 규칙으로 (비용·예측 가능성).

    incident 수신 → monitor → analysis → (P1/P2 만) action → 종료. P3 는 보고만 하고 종료.
    P1·P2 + confidence < 임계값은 LLM 이 재분석/조치 진행을 판단한다 (ADR-0008).
    """
    visits = state.get("supervisor_visits", 0) + 1
    if visits > MAX_SUPERVISOR_VISITS:
        return {
            "supervisor_decision": DONE,
            "supervisor_visits": visits,
            "messages": [
                AIMessage(
                    content=(
                        f"[supervisor] 에스컬레이션: 방문 한도({MAX_SUPERVISOR_VISITS}회) "
                        "초과 — 강제 종료. 사람 확인 필요 (전달 경로는 control-plane 연동 후)"
                    )
                )
            ],
        }

    update: dict = {"supervisor_visits": visits}
    if state.get("monitoring") is None:
        decision = MONITOR
    elif (analysis := state.get("analysis")) is None:
        decision = ANALYSIS
    elif state.get("action") is not None or analysis.severity == "P3":
        decision = DONE
    elif analysis.confidence >= CONFIDENCE_THRESHOLD:
        decision = ACTION
    else:
        route = router.decide_ambiguous_route(analysis)
        decision = route.next
        update["messages"] = [
            AIMessage(content=f"[supervisor] LLM 라우팅 → {route.next}: {route.reason}")
        ]
    update["supervisor_decision"] = decision
    return update


def _route(state: AIOpsState) -> str:
    return state["supervisor_decision"]


def build_graph(checkpointer=None) -> CompiledStateGraph:
    """Supervisor → 에이전트 → Supervisor 순환 구조. checkpointer 는 DAY 12 에 PostgreSQL 로."""
    builder = StateGraph(AIOpsState)

    builder.add_node("supervisor", supervisor_node)
    builder.add_node(MONITOR, monitor_node)
    builder.add_node(ANALYSIS, analysis_node)
    builder.add_node(ACTION, action_node)

    builder.add_edge(START, "supervisor")
    builder.add_conditional_edges(
        "supervisor",
        _route,
        {MONITOR: MONITOR, ANALYSIS: ANALYSIS, ACTION: ACTION, DONE: END},
    )
    # 각 에이전트는 작업 후 반드시 Supervisor 로 복귀 — 라우팅 결정 지점을 한 곳으로 유지
    builder.add_edge(MONITOR, "supervisor")
    builder.add_edge(ANALYSIS, "supervisor")
    builder.add_edge(ACTION, "supervisor")

    return builder.compile(checkpointer=checkpointer)
