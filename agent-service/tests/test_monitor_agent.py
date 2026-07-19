"""monitor_node 테스트 — LLM 에이전트는 스텁으로 대체해 상태 매핑 규약만 검증한다.

실 LLM 단독 테스트는 scripts/run_monitor_agent.py (ANTHROPIC_API_KEY + 스택 필요).
"""

from langchain_core.messages import AIMessage, HumanMessage, ToolMessage

from app.agents import monitor_agent
from app.supervisor.state import IncidentInfo


class _StubAgent:
    """create_agent 결과와 동일한 규약: invoke({"messages": [...]}) -> {"messages": [...]}."""

    def invoke(self, payload: dict) -> dict:
        return {
            "messages": [
                HumanMessage(content=payload["messages"][0].content),
                AIMessage(
                    content="",
                    tool_calls=[
                        {"name": "query_prometheus", "args": {"promql": "up"}, "id": "call-1"}
                    ],
                ),
                ToolMessage(content="[]", tool_call_id="call-1"),
                AIMessage(content="p95 latency 가 5분간 3s 를 초과했다. /products 엔드포인트 집중."),
            ]
        }


def _incident() -> IncidentInfo:
    return IncidentInfo(
        id="inc-test-001",
        scenario="latency-surge",
        alert_name="TargetAppHighLatency",
        summary="p95 latency 3s 초과",
        occurred_at="2026-07-17T14:00:00+09:00",
    )


def test_monitor_node_maps_final_message_to_summary(monkeypatch):
    monkeypatch.setattr(monitor_agent, "get_monitor_agent", lambda: _StubAgent())

    update = monitor_agent.monitor_node({"incident": _incident(), "messages": []})

    assert (
        update["monitoring"].situation_summary
        == "p95 latency 가 5분간 3s 를 초과했다. /products 엔드포인트 집중."
    )


def test_monitor_node_extracts_text_from_content_blocks(monkeypatch):
    """최종 메시지 content 가 콘텐츠 블록 리스트여도 텍스트만 요약으로 추출한다.

    실측 재현 (2026-07-19): 모델이 thinking 블록을 포함해 응답하면 content 가
    list[dict] 로 온다 — 문자열 가정이 pydantic 검증에서 깨졌다.
    """

    class _BlockContentAgent(_StubAgent):
        def invoke(self, payload: dict) -> dict:
            result = super().invoke(payload)
            result["messages"][-1] = AIMessage(
                content=[
                    {"type": "thinking", "thinking": "...", "signature": "sig"},
                    {"type": "text", "text": "p95 latency 정상 범위."},
                ]
            )
            return result

    monkeypatch.setattr(monitor_agent, "get_monitor_agent", lambda: _BlockContentAgent())

    update = monitor_agent.monitor_node({"incident": _incident(), "messages": []})

    assert update["monitoring"].situation_summary == "p95 latency 정상 범위."


def test_monitor_node_records_tool_calls_as_evidences(monkeypatch):
    monkeypatch.setattr(monitor_agent, "get_monitor_agent", lambda: _StubAgent())

    update = monitor_agent.monitor_node({"incident": _incident(), "messages": []})

    # 에이전트가 실제 실행한 쿼리가 근거로 남는다
    assert update["monitoring"].evidences == ['query_prometheus({"promql": "up"})']


def test_monitor_node_keeps_messages_convention(monkeypatch):
    monkeypatch.setattr(monitor_agent, "get_monitor_agent", lambda: _StubAgent())

    update = monitor_agent.monitor_node({"incident": _incident(), "messages": []})

    # 기존 관례 유지: [monitor] 접두어 요약 1건만 그래프 messages 에 남긴다
    assert len(update["messages"]) == 1
    assert update["messages"][0].content.startswith("[monitor]")
