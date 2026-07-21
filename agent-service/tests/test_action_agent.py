"""action_node 테스트 — LLM 에이전트는 스텁으로 대체해 상태 매핑 규약만 검증한다.

monitor/analysis 와 동일한 스텁 주입 패턴 (3번째 적용).
"""


import asyncio
from langchain_core.messages import AIMessage, HumanMessage

from app.agents import action_agent
from app.supervisor.state import ActionPlan, AnalysisResult, IncidentInfo


class _StubAgent:
    """create_agent(response_format=ActionPlan) 규약: structured_response 키로 결과 반환."""

    def __init__(self) -> None:
        self.captured_payload: dict | None = None

    async def ainvoke(self, payload: dict, config: dict | None = None) -> dict:
        self.captured_payload = payload
        return {
            "messages": [
                HumanMessage(content=payload["messages"][0].content),
                AIMessage(content="계획 수립 완료"),
            ],
            "structured_response": ActionPlan(
                actions=["RESTART_APP", "NOTIFY_ONLY"],
                rationale="누수 임시 완화를 위한 재시작 + 담당자 알림",
                expected_effect="heap 회수로 OOM 재발 지연",
                risk="재시작 중 요청 유실 (승인 필요 조치)",
            ),
        }


def _state() -> dict:
    return {
        "incident": IncidentInfo(
            id="inc-test-001",
            scenario="memory-pressure",
            alert_name="TargetAppHeapUsageHigh",
            summary="heap 사용률 85% 초과",
            occurred_at="2026-07-19T10:00:00+09:00",
        ),
        "analysis": AnalysisResult(
            root_cause_hypothesis="스케줄링 태스크 메모리 누수",
            evidence=["Old Gen 1시간 전 대비 20배 증가"],
            confidence=0.95,
            suggested_actions=["컨테이너 재시작"],
            severity="P1",
        ),
        "messages": [],
    }


def test_action_node_maps_structured_response(monkeypatch):
    stub = _StubAgent()
    monkeypatch.setattr(action_agent, "get_action_agent", lambda: stub)

    update = asyncio.run(action_agent.action_node(_state()))

    plan = update["action"]
    assert isinstance(plan, ActionPlan)
    assert plan.actions == ["RESTART_APP", "NOTIFY_ONLY"]
    # scenarios.md 조치 제안서 스펙 — 근거·예상 효과·리스크
    assert plan.expected_effect
    assert plan.risk


def test_action_node_task_includes_analysis_report(monkeypatch):
    stub = _StubAgent()
    monkeypatch.setattr(action_agent, "get_action_agent", lambda: stub)

    asyncio.run(action_agent.action_node(_state()))

    # 조치 계획의 입력은 분석 보고서 — 상황 파악을 처음부터 다시 하지 않는다
    task_content = stub.captured_payload["messages"][0].content
    assert "스케줄링 태스크 메모리 누수" in task_content
    assert "P1" in task_content
    assert "컨테이너 재시작" in task_content


def test_action_node_keeps_messages_convention(monkeypatch):
    stub = _StubAgent()
    monkeypatch.setattr(action_agent, "get_action_agent", lambda: stub)

    update = asyncio.run(action_agent.action_node(_state()))

    # 기존 관례 유지: [action] 접두어 요약 1건만 그래프 messages 에 남긴다
    assert len(update["messages"]) == 1
    assert update["messages"][0].content.startswith("[action]")
    assert "RESTART_APP" in update["messages"][0].content
