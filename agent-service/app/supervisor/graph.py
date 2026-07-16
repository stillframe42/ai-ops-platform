"""Supervisor 오케스트레이션 그래프.

개별 에이전트는 create_agent 로 간결하게, 오케스트레이션은 StateGraph 로 명시적으로 구성한다.
현재 라우팅은 규칙 기반 골격 — DAY 11 에서 하이브리드(명확한 전이=규칙, 모호한 판단=LLM)로
확장하고 ADR-0008 로 기록한다. 무한 루프 방지(방문 카운터)도 DAY 11 몫.
"""

from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph

from app.agents.action_agent import action_node
from app.agents.analysis_agent import analysis_node
from app.agents.monitor_agent import monitor_node
from app.supervisor.state import AIOpsState

# Supervisor 라우팅 결정값
MONITOR = "monitor"
ANALYSIS = "analysis"
ACTION = "action"
DONE = "done"


def supervisor_node(state: AIOpsState) -> dict:
    """상태를 보고 다음 에이전트를 결정한다 — 명확한 전이는 규칙으로 (비용·예측 가능성).

    incident 수신 → monitor → analysis → (P1/P2 만) action → 종료.
    P3 는 보고만 하고 종료한다.
    """
    if state.get("monitoring") is None:
        decision = MONITOR
    elif (analysis := state.get("analysis")) is None:
        decision = ANALYSIS
    elif state.get("action") is None and analysis.severity in ("P1", "P2"):
        decision = ACTION
    else:
        decision = DONE
    return {"supervisor_decision": decision}


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
