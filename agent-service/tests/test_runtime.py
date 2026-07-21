"""GraphRuntime 체크포인터 계약 테스트 — InMemorySaver 로 재개·상태 조회를 검증한다.

핵심 단언: 중단(노드 예외) 후 재개하면 완료된 노드는 재실행되지 않는다 (Durable Execution).
실 PostgreSQL 은 실측(Phase 4)에서 — 체크포인터 인터페이스가 같아 계약은 여기서 고정한다.
"""

import asyncio
from types import SimpleNamespace

import pytest
from langchain_core.messages import AIMessage
from langgraph.checkpoint.memory import InMemorySaver

from app.agents import action_agent, analysis_agent, monitor_agent
from app.supervisor import router
from app.supervisor.graph import build_graph
from app.supervisor.runtime import GraphRuntime, build_incident
from app.supervisor.state import ActionPlan, AnalysisResult


class _CountingMonitorAgent:
    def __init__(self) -> None:
        self.calls = 0

    async def ainvoke(self, payload: dict) -> dict:
        self.calls += 1
        return {"messages": [AIMessage(content="[스텁] 상황 요약")]}


class _CrashableAnalysisAgent:
    """crash_remaining 회만큼 예외를 던진다 — 실행 중 프로세스 강제 종료를 노드 예외로 재현."""

    def __init__(self) -> None:
        self.calls = 0
        self.crash_remaining = 0

    async def ainvoke(self, payload: dict, config: dict | None = None) -> dict:
        self.calls += 1
        if self.crash_remaining > 0:
            self.crash_remaining -= 1
            raise RuntimeError("[스텁] 분석 중 강제 종료 재현")
        return {
            "messages": [AIMessage(content="[스텁] 분석 완료")],
            "structured_response": AnalysisResult(
                root_cause_hypothesis="[스텁] 근본 원인", confidence=0.9, severity="P2"
            ),
        }


class _StubActionAgent:
    async def ainvoke(self, payload: dict, config: dict | None = None) -> dict:
        return {
            "messages": [AIMessage(content="[스텁] 계획 수립 완료")],
            "structured_response": ActionPlan(actions=["NOTIFY_ONLY"], rationale="[스텁] 계획"),
        }


@pytest.fixture(autouse=True)
def stubs(monkeypatch) -> SimpleNamespace:
    ns = SimpleNamespace(
        monitor=_CountingMonitorAgent(),
        analysis=_CrashableAnalysisAgent(),
        action=_StubActionAgent(),
    )
    monkeypatch.setattr(monitor_agent, "get_monitor_agent", lambda: ns.monitor)
    monkeypatch.setattr(analysis_agent, "get_analysis_agent", lambda: ns.analysis)
    monkeypatch.setattr(action_agent, "get_action_agent", lambda: ns.action)

    # 규칙 경로만 지나는 테스트 — LLM 라우터가 호출되면 실 LLM 유출이므로 실패
    def _fail():
        raise AssertionError("런타임 테스트에서 LLM 라우터가 호출됨")

    monkeypatch.setattr(router, "get_route_llm", _fail)
    return ns


def _runtime() -> GraphRuntime:
    return GraphRuntime(build_graph(checkpointer=InMemorySaver()))


def test_build_incident_applies_scenario_preset() -> None:
    incident = build_incident("memory-pressure", incident_id="inc-preset-001")
    assert incident.id == "inc-preset-001"
    assert incident.alert_name == "TargetAppHeapUsageHigh"
    assert incident.scenario == "memory-pressure"


def test_start_records_checkpoints_under_incident_thread() -> None:
    """thread_id = incident.id — 실행 후 같은 id 로 상태가 조회된다."""
    runtime = _runtime()

    async def run() -> dict:
        await runtime.start(build_incident("latency-surge", incident_id="inc-thread-001"))
        return await runtime.get_state("inc-thread-001")

    state = asyncio.run(run())
    assert state is not None
    assert state["incident_id"] == "inc-thread-001"
    assert state["done"] is True
    assert state["completed"] == {"monitoring": True, "analysis": True, "action": True}
    assert state["supervisor_decision"] == "done"


def test_get_state_returns_none_for_unknown_incident() -> None:
    runtime = _runtime()
    assert asyncio.run(runtime.get_state("inc-unknown")) is None


def test_resume_does_not_rerun_completed_nodes(stubs: SimpleNamespace) -> None:
    """분석 직전 중단 → 재개: monitor 는 재실행되지 않고 분석부터 이어간다 (토큰 절약의 근거).

    중단은 interrupt_before 로 재현한다 — SIGKILL 이 남기는 것과 동일한
    "monitoring 완료 + next=analysis" 체크포인트 상태 (DAY 12 실측과 같은 모양).
    노드 예외는 DAY 13 부터 error_handler 가 우아하게 처리하므로 중단 재현 수단이 아니다.
    """
    runtime = _runtime()
    incident = build_incident("memory-pressure", incident_id="inc-interrupt-001")

    async def run() -> tuple[dict, dict]:
        await runtime.graph.ainvoke(
            {"incident": incident, "messages": []},
            config=runtime._config(incident.id),
            interrupt_before=["analysis"],
        )
        interrupted = await runtime.get_state(incident.id)
        await runtime.resume(incident.id)
        return interrupted, await runtime.get_state(incident.id)

    interrupted, final = asyncio.run(run())
    # 중단 시점: monitor 결과는 체크포인트에 남았고, analysis 가 재개 대상으로 남아 있다
    assert interrupted["done"] is False
    assert interrupted["completed"]["monitoring"] is True
    assert "analysis" in interrupted["next"]
    # 재개 후 완주 — monitor 1회(재실행 없음), analysis 는 재개 시 1회뿐
    assert final["done"] is True
    assert stubs.monitor.calls == 1
    assert stubs.analysis.calls == 1


def test_get_state_exposes_recorded_errors(stubs: SimpleNamespace) -> None:
    """노드 실패 기록(errors)이 상태 조회에 노출된다 — API 소비자용 dict 직렬화."""
    stubs.analysis.crash_remaining = 1  # 1회 실패 → error_handler 기록 → 에스컬레이션 종료
    runtime = _runtime()

    async def run() -> dict:
        await runtime.start(build_incident("latency-surge", incident_id="inc-err-001"))
        return await runtime.get_state("inc-err-001")

    state = asyncio.run(run())
    assert state["done"] is True  # 실패해도 우아하게 종료 (부분 보고서)
    assert state["completed"] == {"monitoring": True, "analysis": False, "action": False}
    (failure,) = state["errors"]
    assert failure["node"] == "analysis"
    assert failure["error_type"] == "RuntimeError"
    assert "강제 종료 재현" in failure["message"]


def test_get_state_surfaces_pending_task_error() -> None:
    """error_handler 가 없는 노드가 죽으면 중단 원인이 pending_errors 로 노출된다."""
    from langgraph.graph import START, StateGraph

    from app.supervisor.state import AIOpsState

    def boom(state: AIOpsState) -> dict:
        raise RuntimeError("[스텁] 핸들러 없는 노드 실패")

    builder = StateGraph(AIOpsState)
    builder.add_node("boom", boom)
    builder.add_edge(START, "boom")
    runtime = GraphRuntime(builder.compile(checkpointer=InMemorySaver()))
    incident = build_incident("latency-surge", incident_id="inc-boom-001")

    async def run() -> dict:
        with pytest.raises(RuntimeError):
            await runtime.graph.ainvoke(
                {"incident": incident, "messages": []}, config=runtime._config(incident.id)
            )
        return await runtime.get_state(incident.id)

    state = asyncio.run(run())
    assert state["done"] is False
    (pending,) = state["pending_errors"]
    assert pending["node"] == "boom"
    assert "핸들러 없는 노드 실패" in pending["error"]


def test_checkpoint_serializer_roundtrips_registered_state_models() -> None:
    """상태 모델 5종이 허용 목록에 등록돼 경고 없이 직렬화 왕복된다 (업그레이드 대비).

    기본(permissive) 직렬화는 미등록 pydantic 타입마다 "향후 차단 예정" 경고를 낸다 —
    명시 등록으로 경고를 없애고, langgraph 업그레이드 시 차단으로 바뀌어도 안전하다.
    """
    from app.supervisor.runtime import build_checkpoint_serializer
    from app.supervisor.state import (
        ActionPlan,
        AnalysisResult,
        MonitoringResult,
        NodeFailure,
    )

    serde = build_checkpoint_serializer()
    samples = [
        build_incident("latency-surge", incident_id="inc-serde-001"),
        MonitoringResult(situation_summary="[스텁] 요약"),
        AnalysisResult(root_cause_hypothesis="[스텁] 가설", confidence=0.9, severity="P2"),
        ActionPlan(actions=["NOTIFY_ONLY"], rationale="[스텁] 계획"),
        NodeFailure(node="monitor", error_type="E", message="m", occurred_at="t"),
    ]
    for sample in samples:
        assert serde.loads_typed(serde.dumps_typed(sample)) == sample


def test_checkpoint_serializer_blocks_unregistered_types() -> None:
    """허용 목록 밖 타입은 모델로 복원되지 않는다 — 새 상태 모델 등록 누락 감지.

    실측: 차단은 예외가 아니라 "Blocked deserialization" 로그 + 평문 dict 강등 —
    모델 복원을 기대한 코드가 즉시 AttributeError 로 드러난다.
    """
    from app.supervisor.router import RouteDecision
    from app.supervisor.runtime import build_checkpoint_serializer

    serde = build_checkpoint_serializer()
    unregistered = RouteDecision(next="action", reason="[스텁] 체크포인트 대상 아님")
    restored = serde.loads_typed(serde.dumps_typed(unregistered))
    assert not isinstance(restored, RouteDecision)  # dict 로 강등된다


def test_history_lists_checkpoints_newest_first() -> None:
    runtime = _runtime()

    async def run() -> list[dict]:
        await runtime.start(build_incident("error-rate-surge", incident_id="inc-hist-001"))
        return await runtime.get_history("inc-hist-001")

    history = asyncio.run(run())
    # 입력 체크포인트 + super-step 마다 1개 — 전체 경로(supervisor 4 + 에이전트 3)면 8개 이상
    assert len(history) >= 8
    assert history[0]["next"] == []  # 최신(종료) 체크포인트가 먼저
    assert all({"step", "next", "created_at"} <= item.keys() for item in history)
    assert asyncio.run(runtime.get_history("inc-unknown")) == []
