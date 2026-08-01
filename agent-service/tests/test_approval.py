"""승인 흐름 테스트 — interrupt 정지·Command(resume) 재개 계약 (ADR-0005).

interrupt 는 체크포인터 전제 — InMemorySaver 로 검증한다 (실 PostgreSQL 은 E2E 몫).
approval 노드는 action → approval 정적 엣지라 P3 는 구조적으로 도달하지 않는다
(P3 조기 종료 회귀는 test_graph.py 몫).
"""

import asyncio

import pytest
from langchain_core.messages import AIMessage
from langgraph.checkpoint.memory import InMemorySaver

from app.agents import action_agent, analysis_agent, monitor_agent
from app.supervisor import router
from app.supervisor.graph import build_graph
from app.supervisor.runtime import GraphRuntime, build_incident
from app.supervisor.state import ActionPlan, AnalysisResult


def _as_async_factory(agent):
    """get_analysis_agent 는 async (MCP 도구 발견 포함, DAY 16) — 스텁을 코루틴으로 감싼다."""

    async def _get():
        return agent

    return _get


class _StubMonitorAgent:
    async def ainvoke(self, payload: dict) -> dict:
        return {"messages": [AIMessage(content="[스텁] 상황 요약")]}


class _StubAnalysisAgent:
    async def ainvoke(self, payload: dict, config: dict | None = None) -> dict:
        return {
            "messages": [AIMessage(content="[스텁] 분석 완료")],
            "structured_response": AnalysisResult(
                root_cause_hypothesis="[스텁] heap 누수 의심", confidence=0.9, severity="P1"
            ),
        }


class _RestartActionAgent:
    """실행 조치 포함 계획 — 승인 대기를 일으키는 쪽."""

    async def ainvoke(self, payload: dict, config: dict | None = None) -> dict:
        return {
            "messages": [AIMessage(content="[스텁] 계획 수립 완료")],
            "structured_response": ActionPlan(
                actions=["RESTART_APP"],
                rationale="[스텁] heap 회복에 재시작 필요",
                expected_effect="[스텁] heap 사용률 정상화",
                risk="[스텁] 재시작 동안 요청 유실",
            ),
        }


class _NotifyOnlyActionAgent:
    async def ainvoke(self, payload: dict, config: dict | None = None) -> dict:
        return {
            "messages": [AIMessage(content="[스텁] 계획 수립 완료")],
            "structured_response": ActionPlan(actions=["NOTIFY_ONLY"], rationale="[스텁] 알림만"),
        }


@pytest.fixture(autouse=True)
def stub_agents(monkeypatch):
    monkeypatch.setattr(monitor_agent, "get_monitor_agent", lambda: _StubMonitorAgent())
    monkeypatch.setattr(
        analysis_agent, "get_analysis_agent", _as_async_factory(_StubAnalysisAgent())
    )
    monkeypatch.setattr(action_agent, "get_action_agent", lambda: _RestartActionAgent())

    # 규칙 경로만 지나는 테스트 — LLM 라우터가 호출되면 실 LLM 유출이므로 실패
    def _fail():
        raise AssertionError("승인 흐름 테스트에서 LLM 라우터가 호출됨")

    monkeypatch.setattr(router, "get_route_llm", _fail)


def _runtime() -> GraphRuntime:
    return GraphRuntime(build_graph(checkpointer=InMemorySaver()))


def test_executable_plan_pauses_for_approval() -> None:
    """P1 + 실행 조치 — approval 에서 정지하고 interrupt 페이로드가 승인 요청서로 노출된다."""
    runtime = _runtime()

    async def run() -> tuple[dict, dict]:
        await runtime.start(build_incident("memory-pressure", incident_id="inc-appr-001"))
        return (
            await runtime.get_state("inc-appr-001"),
            await runtime.get_pending_approval("inc-appr-001"),
        )

    state, request = asyncio.run(run())
    assert state["done"] is False
    assert state["awaiting_approval"] is True
    # 승인 요청서 = ops.actions.pending 페이로드 겸 Slack 승인 카드 재료 (ADR-0006 스펙)
    assert request["incident_id"] == "inc-appr-001"
    assert request["severity"] == "P1"
    assert request["confidence"] == 0.9
    assert request["actions"] == ["RESTART_APP"]
    assert request["rationale"]
    assert request["risk"]
    assert request["requested_at"]


def test_approved_decision_resumes_to_completion() -> None:
    runtime = _runtime()

    async def run() -> tuple[dict, dict]:
        await runtime.start(build_incident("memory-pressure", incident_id="inc-appr-002"))
        await runtime.resume_with_decision(
            "inc-appr-002",
            {"status": "approved", "decided_by": "U0123ABC", "note": "재시작 실행 완료"},
        )
        return (
            await runtime.get_state("inc-appr-002"),
            await runtime.get_result("inc-appr-002"),
        )

    state, result = asyncio.run(run())
    assert state["done"] is True
    assert state["awaiting_approval"] is False
    assert result["status"] == "completed"
    # 결과 보고서에 승인 감사 정보가 실린다 — control-plane 보고서의 입력
    assert result["approval"] == {
        "status": "approved",
        "decided_by": "U0123ABC",
        "note": "재시작 실행 완료",
    }


def test_rejected_decision_ends_without_execution() -> None:
    """거부 = 조치 미실행 종결 — 그래프는 정상 완주하고 결정만 기록한다 (ADR-0006)."""
    runtime = _runtime()

    async def run() -> dict:
        await runtime.start(build_incident("memory-pressure", incident_id="inc-appr-003"))
        await runtime.resume_with_decision(
            "inc-appr-003", {"status": "rejected", "decided_by": "U0123ABC"}
        )
        return await runtime.get_result("inc-appr-003")

    result = asyncio.run(run())
    assert result["status"] == "completed"
    assert result["approval"]["status"] == "rejected"


def test_unknown_decision_payload_is_rejected_safely() -> None:
    """알 수 없는 결정 페이로드는 안전 측 거부 — 승인 없이 실행에 도달하는 경로를 막는다."""
    runtime = _runtime()

    async def run() -> dict:
        await runtime.start(build_incident("memory-pressure", incident_id="inc-appr-004"))
        await runtime.resume_with_decision("inc-appr-004", {"status": "yolo"})
        return await runtime.get_result("inc-appr-004")

    result = asyncio.run(run())
    assert result["approval"]["status"] == "rejected"
    assert "알 수 없는 결정" in result["approval"]["note"]


def test_notify_only_plan_skips_approval(monkeypatch) -> None:
    """알림뿐인 계획은 인프라 변경이 없다 — 승인 왕복 없이 한 번에 완주한다."""
    monkeypatch.setattr(action_agent, "get_action_agent", lambda: _NotifyOnlyActionAgent())
    runtime = _runtime()

    async def run() -> tuple[dict, dict]:
        await runtime.start(build_incident("memory-pressure", incident_id="inc-appr-005"))
        return (
            await runtime.get_state("inc-appr-005"),
            await runtime.get_result("inc-appr-005"),
        )

    state, result = asyncio.run(run())
    assert state["done"] is True
    assert state["awaiting_approval"] is False
    assert result["approval"]["status"] == "skipped"
