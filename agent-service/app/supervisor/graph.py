"""Supervisor 오케스트레이션 그래프.

개별 에이전트는 create_agent 로 간결하게, 오케스트레이션은 StateGraph 로 명시적으로 구성한다.
라우팅은 하이브리드 (ADR-0008): 명확한 전이는 규칙, 모호한 판단(낮은 confidence)만 LLM.
"""

from datetime import UTC, datetime

import httpx
from langchain_core.messages import AIMessage
from langgraph.errors import NodeError, NodeTimeoutError
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph
from langgraph.types import Command, RetryPolicy

from app.agents.action_agent import action_node
from app.agents.analysis_agent import analysis_node
from app.agents.monitor_agent import monitor_node
from app.supervisor import router
from app.supervisor.state import AIOpsState, NodeFailure

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

# 노드별 타임아웃 (초) — 협조적 취소(asyncio) 기반이라 에이전트 노드가 async 인 것이 전제.
# 분석은 도구 호출 루프가 길어 여유를 준다 (DAY 12 실측: 정상 노드 8~31초)
NODE_TIMEOUTS: dict[str, float] = {MONITOR: 60.0, ANALYSIS: 180.0, ACTION: 120.0}


def retry_on_transient(exc: Exception) -> bool:
    """일시적 오류만 재시도한다 — 허용 목록 방식.

    langgraph 기본 정책(default_retry_on)은 "모르는 예외는 재시도"라서 NodeTimeoutError 도
    재시도 대상이 된다 — 타임아웃 재시도는 대기를 반복할 뿐이라 (분석 180s × 3회) 명시적으로
    제외한다. 프로그래밍 오류(ValueError 등)는 재시도해도 결과가 같으므로 목록에 없다.
    """
    if isinstance(exc, NodeTimeoutError):
        return False
    if isinstance(exc, httpx.HTTPStatusError):
        return exc.response.status_code >= 500  # 서버 측 오류만 — 4xx 는 요청 자체의 문제
    return isinstance(exc, (ConnectionError, httpx.RequestError))


# 에이전트 노드 공통 재시도 — 지수 백오프 기본값 (0.5s 시작, 배수 2.0, 최대 3회 시도)
AGENT_RETRY_POLICY = RetryPolicy(retry_on=retry_on_transient)


def record_node_failure(state: AIOpsState, error: NodeError) -> Command:
    """에이전트 노드 실패를 상태에 기록하고 supervisor 로 복귀한다 (재시도 소진 후 호출).

    실패해도 실행은 계속된다 — 라우팅 결정 지점을 supervisor 한 곳으로 유지하는 원칙 그대로,
    "실패 이후 어디로 갈지"도 supervisor 가 errors 기록을 보고 판단한다.
    """
    failure = NodeFailure(
        node=error.node,
        error_type=type(error.error).__name__,
        message=str(error.error),
        occurred_at=datetime.now(UTC).isoformat(),
    )
    return Command(
        update={
            "errors": [failure],
            "messages": [
                AIMessage(
                    content=f"[{error.node}] 노드 실패 ({failure.error_type}): {failure.message}"
                )
            ],
        },
        goto="supervisor",
    )


def _failed_nodes(state: AIOpsState) -> set[str]:
    return {failure.node for failure in state.get("errors") or []}


def supervisor_node(state: AIOpsState) -> dict:
    """상태를 보고 다음 에이전트를 결정한다 — 명확한 전이는 규칙으로 (비용·예측 가능성).

    incident 수신 → monitor → analysis → (P1/P2 만) action → 종료. P3 는 보고만 하고 종료.
    P1·P2 + confidence < 임계값은 LLM 이 재분석/조치 진행을 판단한다 (ADR-0008).
    실패한 노드는 '시도됨'으로 판정해 재진입하지 않는다 — monitor 실패는 analysis 로
    부분 진행, analysis 실패는 에스컬레이션 종료, action 실패는 종료 (부분 보고서).
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
    failed = _failed_nodes(state)
    if state.get("monitoring") is None and MONITOR not in failed:
        decision = MONITOR
    elif (analysis := state.get("analysis")) is None:
        if ANALYSIS in failed:
            # 분석 없이는 조치 판단이 불가 — 확보된 것까지만 부분 보고서로 남기고 사람에게
            decision = DONE
            update["messages"] = [
                AIMessage(
                    content=(
                        "[supervisor] 에스컬레이션: 분석 단계 실패 — 부분 보고서로 종료. "
                        "사람 확인 필요 (전달 경로는 control-plane 연동 후)"
                    )
                )
            ]
        else:
            decision = ANALYSIS
    elif state.get("action") is not None or analysis.severity == "P3" or ACTION in failed:
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
    # 에이전트 노드 실패는 error_handler 가 상태에 기록 — 전체 실행을 죽이지 않는다 (DAY 13).
    # 타임아웃 초과도 같은 경로 (NodeTimeoutError → record_node_failure)
    for name, node in ((MONITOR, monitor_node), (ANALYSIS, analysis_node), (ACTION, action_node)):
        builder.add_node(
            name,
            node,
            error_handler=record_node_failure,
            retry_policy=AGENT_RETRY_POLICY,
            timeout=NODE_TIMEOUTS[name],
        )

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
