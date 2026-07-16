"""DAY 8 확인 기준: 빈 노드 3개 + Supervisor 그래프가 컴파일되고 더미 상태로 end-to-end 실행된다."""

from app.supervisor.graph import build_graph
from app.supervisor.state import AnalysisResult, IncidentInfo, MonitoringResult


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
