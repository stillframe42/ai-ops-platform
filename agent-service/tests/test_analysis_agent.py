"""analysis_node 테스트 — LLM 에이전트는 스텁으로 대체해 상태 매핑 규약만 검증한다.

monitor_agent 와 동일 패턴 + 구조화 출력(structured_response) 규약 검증.
실 LLM 단독 테스트는 scripts/run_analysis_agent.py (ANTHROPIC_API_KEY + 스택 필요).
"""


import asyncio
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage

from app.agents import analysis_agent
from app.supervisor.state import AnalysisResult, IncidentInfo, MonitoringResult


class _StubAgent:
    """create_agent(response_format=AnalysisResult) 규약: structured_response 키로 결과 반환."""

    def __init__(self) -> None:
        self.captured_payload: dict | None = None
        self.captured_config: dict | None = None

    async def ainvoke(self, payload: dict, config: dict | None = None) -> dict:
        self.captured_payload = payload
        self.captured_config = config
        return {
            "messages": [
                HumanMessage(content=payload["messages"][0].content),
                AIMessage(
                    content="",
                    tool_calls=[
                        {"name": "get_app_logs", "args": {"minutes": 10, "level": "ERROR"}, "id": "call-1"}
                    ],
                ),
                ToolMessage(content="[]", tool_call_id="call-1"),
                AIMessage(content="분석 완료"),
            ],
            "structured_response": AnalysisResult(
                root_cause_hypothesis="chaos error-rate fault 로 인한 의도적 500 응답",
                evidence=["ERROR 로그에 ChaosInterceptor 발생 기록"],
                confidence=0.9,
                suggested_actions=["chaos 설정 원복"],
                severity="P2",
            ),
        }


def _state() -> dict:
    return {
        "incident": IncidentInfo(
            id="inc-test-001",
            scenario="error-rate-surge",
            alert_name="TargetAppHighErrorRate",
            summary="5xx 에러율 10% 초과",
            occurred_at="2026-07-18T14:00:00+09:00",
        ),
        "monitoring": MonitoringResult(
            situation_summary="/products 에서 5xx 에러율 47% 관측",
            evidences=['query_prometheus({"promql": "..."})'],
        ),
        "messages": [],
    }


def test_analysis_result_schema_requires_confidence():
    """confidence 는 구조화 출력 스키마에서 필수 필드여야 한다.

    실측 재현 (2026-07-19): 기본값이 있으면 선택 필드가 돼 모델이 생략할 수 있고,
    생략이 0.0 으로 위장되면 라우팅(confidence 임계 판정) 입력이 왜곡된다.
    """
    assert "confidence" in AnalysisResult.model_json_schema()["required"]


def test_analysis_node_maps_structured_response(monkeypatch):
    stub = _StubAgent()
    monkeypatch.setattr(analysis_agent, "get_analysis_agent", lambda: stub)

    update = asyncio.run(analysis_agent.analysis_node(_state()))

    analysis = update["analysis"]
    assert isinstance(analysis, AnalysisResult)
    assert analysis.root_cause_hypothesis == "chaos error-rate fault 로 인한 의도적 500 응답"
    assert analysis.severity == "P2"


def test_analysis_node_task_includes_monitoring_summary(monkeypatch):
    stub = _StubAgent()
    monkeypatch.setattr(analysis_agent, "get_analysis_agent", lambda: stub)

    asyncio.run(analysis_agent.analysis_node(_state()))

    # 분석 입력은 모니터링 요약 — 상황 파악을 처음부터 다시 하지 않는다
    task_content = stub.captured_payload["messages"][0].content
    assert "/products 에서 5xx 에러율 47% 관측" in task_content
    assert "error-rate-surge" in task_content


def test_analysis_node_sets_recursion_limit(monkeypatch):
    stub = _StubAgent()
    monkeypatch.setattr(analysis_agent, "get_analysis_agent", lambda: stub)

    asyncio.run(analysis_agent.analysis_node(_state()))

    # ReAct 무한 루프 방지 — 최대 스텝 제한 (5월 패턴)
    assert stub.captured_config["recursion_limit"] == analysis_agent.ANALYSIS_RECURSION_LIMIT


def test_analysis_node_keeps_messages_convention(monkeypatch):
    stub = _StubAgent()
    monkeypatch.setattr(analysis_agent, "get_analysis_agent", lambda: stub)

    update = asyncio.run(analysis_agent.analysis_node(_state()))

    # 기존 관례 유지: [analysis] 접두어 요약 1건만 그래프 messages 에 남긴다
    assert len(update["messages"]) == 1
    assert update["messages"][0].content.startswith("[analysis]")
    assert "chaos error-rate fault" in update["messages"][0].content
