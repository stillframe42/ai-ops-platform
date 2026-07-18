"""Supervisor 그래프 라우팅 테스트 — 실 LLM 없이 라우팅 규칙만 검증한다.

DAY 9 부터 monitor, DAY 10 부터 analysis 노드가 실 LLM 에이전트이므로 스텁을 주입한다
(라우팅 회귀망 유지).
"""

import pytest
from langchain_core.messages import AIMessage

from app.agents import analysis_agent, monitor_agent
from app.supervisor.graph import build_graph
from app.supervisor.state import AnalysisResult, IncidentInfo, MonitoringResult


class _StubMonitorAgent:
    def invoke(self, payload: dict) -> dict:
        return {"messages": [AIMessage(content="[스텁] 상황 요약")]}


class _StubAnalysisAgent:
    def invoke(self, payload: dict, config: dict | None = None) -> dict:
        return {
            "messages": [AIMessage(content="[스텁] 분석 완료")],
            # 더미 시절과 동일하게 P2 — action 경로까지 end-to-end 로 흐르게 한다
            "structured_response": AnalysisResult(
                root_cause_hypothesis="[스텁] 근본 원인", severity="P2"
            ),
        }


@pytest.fixture(autouse=True)
def stub_agents(monkeypatch):
    monkeypatch.setattr(monitor_agent, "get_monitor_agent", lambda: _StubMonitorAgent())
    monkeypatch.setattr(analysis_agent, "get_analysis_agent", lambda: _StubAnalysisAgent())


def _dummy_incident() -> IncidentInfo:
    return IncidentInfo(
        id="inc-test-001",
        scenario="latency-surge",
        alert_name="TargetAppHighLatency",
        summary="p95 latency 3s 초과 (테스트 더미)",
        occurred_at="2026-07-16T14:00:00+09:00",
    )


def test_graph_compiles() -> None:
    graph = build_graph()
    assert graph is not None


def test_dummy_end_to_end() -> None:
    """monitor → analysis → action(더미 P2) → 종료까지 전 노드를 거친다."""
    graph = build_graph()
    result = graph.invoke({"incident": _dummy_incident(), "messages": []})

    assert result["monitoring"] is not None
    assert result["analysis"] is not None
    assert result["action"] is not None
    assert result["supervisor_decision"] == "done"
    # 각 에이전트가 messages 에 흔적을 남긴다 (monitor/analysis/action 3건)
    assert len(result["messages"]) == 3


def test_p3_skips_action() -> None:
    """analysis 가 P3 면 action 없이 보고만 하고 종료한다 — 조기 종료 경로."""
    graph = build_graph()
    result = graph.invoke(
        {
            "incident": _dummy_incident(),
            "monitoring": MonitoringResult(situation_summary="사전 주입"),
            "analysis": AnalysisResult(root_cause_hypothesis="사전 주입", severity="P3"),
            "messages": [],
        }
    )

    assert result.get("action") is None
    assert result["supervisor_decision"] == "done"


def test_health_endpoint() -> None:
    from fastapi.testclient import TestClient

    from app.main import app

    response = TestClient(app).get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"
