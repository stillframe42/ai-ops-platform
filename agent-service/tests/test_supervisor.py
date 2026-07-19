"""supervisor_node 하이브리드 라우팅 테스트 — 규칙 경로는 LLM 무호출을 함께 검증한다.

명확한 전이 = 규칙, 모호한 판단(P1·P2 + 낮은 confidence) = LLM 위임 (ADR-0008).
무한 루프 방지: supervisor_visits 카운터 5회 초과 시 강제 종료 + 에스컬레이션.
"""

import pytest

from app.supervisor import router
from app.supervisor.graph import supervisor_node
from app.supervisor.router import RouteDecision
from app.supervisor.state import (
    ActionPlan,
    AnalysisResult,
    IncidentInfo,
    MonitoringResult,
)


class _StubRouteLLM:
    """create_llm().with_structured_output(RouteDecision) 규약의 스텁."""

    def __init__(self, next_: str) -> None:
        self.next = next_
        self.invoked = False

    def invoke(self, prompt) -> RouteDecision:
        self.invoked = True
        return RouteDecision(next=self.next, reason="[스텁] 라우팅 판단")


@pytest.fixture(autouse=True)
def forbid_route_llm(monkeypatch):
    """규칙 경로 테스트에서 LLM 라우터가 호출되면 실패시킨다 (실 LLM 호출 차단 겸용)."""

    def _fail():
        raise AssertionError("규칙으로 결정 가능한 전이에서 LLM 라우터가 호출됨")

    monkeypatch.setattr(router, "get_route_llm", _fail)


def _incident() -> IncidentInfo:
    return IncidentInfo(
        id="inc-test-001",
        scenario="memory-pressure",
        alert_name="TargetAppHeapUsageHigh",
        summary="heap 사용률 85% 초과",
        occurred_at="2026-07-19T10:00:00+09:00",
    )


def _analysis(severity: str, confidence: float) -> AnalysisResult:
    return AnalysisResult(
        root_cause_hypothesis="스케줄링 태스크 메모리 누수",
        confidence=confidence,
        severity=severity,
    )


# --- 규칙 경로 (LLM 무호출) ---


def test_rule_no_monitoring_routes_to_monitor():
    update = supervisor_node({"incident": _incident(), "messages": []})
    assert update["supervisor_decision"] == "monitor"


def test_rule_no_analysis_routes_to_analysis():
    update = supervisor_node(
        {
            "incident": _incident(),
            "monitoring": MonitoringResult(situation_summary="heap 98%"),
            "messages": [],
        }
    )
    assert update["supervisor_decision"] == "analysis"


def test_rule_high_confidence_p1_routes_to_action():
    """DAY 10 실측 사례 (P1, confidence 0.95) — 규칙만으로 action 결정."""
    update = supervisor_node(
        {
            "incident": _incident(),
            "monitoring": MonitoringResult(situation_summary="heap 98%"),
            "analysis": _analysis("P1", 0.95),
            "messages": [],
        }
    )
    assert update["supervisor_decision"] == "action"


def test_rule_p3_routes_to_done():
    update = supervisor_node(
        {
            "incident": _incident(),
            "monitoring": MonitoringResult(situation_summary="정상 범위"),
            "analysis": _analysis("P3", 0.2),  # P3 는 confidence 무관 조기 종료
            "messages": [],
        }
    )
    assert update["supervisor_decision"] == "done"


def test_rule_action_done_routes_to_done():
    update = supervisor_node(
        {
            "incident": _incident(),
            "monitoring": MonitoringResult(situation_summary="heap 98%"),
            "analysis": _analysis("P1", 0.95),
            "action": ActionPlan(actions=["RESTART_APP"], rationale="누수 임시 완화"),
            "messages": [],
        }
    )
    assert update["supervisor_decision"] == "done"


# --- 모호 구간 (LLM 위임) ---


def test_low_confidence_p2_delegates_to_llm(monkeypatch):
    """P1·P2 + confidence < 0.6 은 규칙으로 못 가름 — LLM 이 analysis/action 중 결정."""
    stub = _StubRouteLLM("analysis")
    monkeypatch.setattr(router, "get_route_llm", lambda: stub)

    update = supervisor_node(
        {
            "incident": _incident(),
            "monitoring": MonitoringResult(situation_summary="heap 98%"),
            "analysis": _analysis("P2", 0.4),
            "messages": [],
        }
    )

    assert stub.invoked
    assert update["supervisor_decision"] == "analysis"


def test_llm_route_leaves_reason_in_messages(monkeypatch):
    """LLM 라우팅 판단은 messages 에 근거를 남긴다 — 라우팅 추적 가능성."""
    monkeypatch.setattr(router, "get_route_llm", lambda: _StubRouteLLM("action"))

    update = supervisor_node(
        {
            "incident": _incident(),
            "monitoring": MonitoringResult(situation_summary="heap 98%"),
            "analysis": _analysis("P2", 0.4),
            "messages": [],
        }
    )

    assert update["supervisor_decision"] == "action"
    assert any(m.content.startswith("[supervisor]") for m in update["messages"])


# --- 무한 루프 방지 ---


def test_visit_counter_increments():
    update = supervisor_node({"incident": _incident(), "messages": []})
    assert update["supervisor_visits"] == 1

    update = supervisor_node(
        {"incident": _incident(), "supervisor_visits": 3, "messages": []}
    )
    assert update["supervisor_visits"] == 4


def test_visit_limit_forces_done_with_escalation():
    """5회 초과(6번째 진입) 시 상태와 무관하게 강제 종료 + 에스컬레이션 메시지."""
    update = supervisor_node(
        {
            "incident": _incident(),
            "supervisor_visits": 5,
            "messages": [],  # monitoring 이 없어도 (규칙상 monitor 대상) 강제 종료
        }
    )

    assert update["supervisor_decision"] == "done"
    assert any("에스컬레이션" in m.content for m in update["messages"])
