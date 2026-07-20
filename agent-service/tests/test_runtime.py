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

    def invoke(self, payload: dict) -> dict:
        self.calls += 1
        return {"messages": [AIMessage(content="[스텁] 상황 요약")]}


class _CrashableAnalysisAgent:
    """crash_remaining 회만큼 예외를 던진다 — 실행 중 프로세스 강제 종료를 노드 예외로 재현."""

    def __init__(self) -> None:
        self.calls = 0
        self.crash_remaining = 0

    def invoke(self, payload: dict, config: dict | None = None) -> dict:
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
    def invoke(self, payload: dict, config: dict | None = None) -> dict:
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
    """분석 중 중단 → 재개: monitor 는 재실행되지 않고 분석부터 이어간다 (토큰 절약의 근거)."""
    stubs.analysis.crash_remaining = 1
    runtime = _runtime()
    incident = build_incident("memory-pressure", incident_id="inc-crash-001")

    async def run() -> tuple[dict, dict]:
        with pytest.raises(RuntimeError):
            await runtime.start(incident)
        interrupted = await runtime.get_state("inc-crash-001")
        await runtime.resume("inc-crash-001")
        return interrupted, await runtime.get_state("inc-crash-001")

    interrupted, final = asyncio.run(run())
    # 중단 시점: monitor 결과는 체크포인트에 남았고, analysis 가 재개 대상으로 남아 있다
    assert interrupted["done"] is False
    assert interrupted["completed"]["monitoring"] is True
    assert "analysis" in interrupted["next"]
    # 재개 후 완주 — monitor 1회(재실행 없음), analysis 는 중단 1 + 재개 1 = 2회
    assert final["done"] is True
    assert stubs.monitor.calls == 1
    assert stubs.analysis.calls == 2


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
