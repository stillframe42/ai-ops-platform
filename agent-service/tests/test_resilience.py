"""복원력 테스트 — 노드 실패가 전체 실행을 죽이지 않고 상태에 기록된다 (DAY 13).

에이전트 노드 실패 → error_handler 가 NodeFailure 를 errors 에 축적하고 supervisor 로 복귀.
supervisor 는 실패한 노드를 '시도됨'으로 판정해 재진입하지 않는다 — 부분 보고서 경로:
monitor 실패 → analysis 부분 진행 / analysis 실패 → 에스컬레이션 종료 / action 실패 → 종료.
"""

import asyncio

import pytest
from langchain_core.messages import AIMessage, HumanMessage

from app.agents import action_agent, analysis_agent, monitor_agent
from app.supervisor import graph as supervisor_graph
from app.supervisor import router
from app.supervisor.graph import build_graph, supervisor_node
from app.supervisor.state import (
    AnalysisResult,
    IncidentInfo,
    MonitoringResult,
    NodeFailure,
)

def _as_async_factory(agent):
    """get_analysis_agent 는 async (MCP 도구 발견 포함, DAY 16) — 스텁을 코루틴으로 감싼다."""

    async def _get():
        return agent

    return _get



class _StubMonitorAgent:
    async def ainvoke(self, payload: dict) -> dict:
        return {"messages": [AIMessage(content="[스텁] 상황 요약")]}


class _FailingAgent:
    """호출 즉시 실패하는 에이전트 — error_handler 경로 검증용."""

    def __init__(self, message: str) -> None:
        self.message = message

    async def ainvoke(self, payload: dict, config: dict | None = None) -> dict:
        raise RuntimeError(self.message)


class _StubAnalysisAgent:
    def __init__(self) -> None:
        self.last_task: HumanMessage | None = None

    async def ainvoke(self, payload: dict, config: dict | None = None) -> dict:
        self.last_task = payload["messages"][0]
        return {
            "messages": [AIMessage(content="[스텁] 분석 완료")],
            # P3 — action 없이 종료하는 최단 경로로 회귀를 좁힌다
            "structured_response": AnalysisResult(
                root_cause_hypothesis="[스텁] 근본 원인", confidence=0.9, severity="P3"
            ),
        }


@pytest.fixture(autouse=True)
def stub_agents(monkeypatch):
    monkeypatch.setattr(monitor_agent, "get_monitor_agent", lambda: _StubMonitorAgent())
    monkeypatch.setattr(analysis_agent, "get_analysis_agent", _as_async_factory(_StubAnalysisAgent()))

    def _fail():
        raise AssertionError("복원력 테스트에서 LLM 라우터가 호출됨")

    monkeypatch.setattr(router, "get_route_llm", _fail)


def _incident() -> IncidentInfo:
    return IncidentInfo(
        id="inc-test-001",
        scenario="latency-surge",
        alert_name="TargetAppHighLatency",
        summary="p95 latency 3s 초과 (테스트 더미)",
        occurred_at="2026-07-21T10:00:00+09:00",
    )


def _failure(node: str) -> NodeFailure:
    return NodeFailure(
        node=node,
        error_type="RuntimeError",
        message=f"[스텁] {node} 실패",
        occurred_at="2026-07-21T10:01:00+09:00",
    )


def _analysis(severity: str, confidence: float) -> AnalysisResult:
    return AnalysisResult(
        root_cause_hypothesis="[스텁] 가설", confidence=confidence, severity=severity
    )


# --- supervisor 실패 인지 라우팅 (단위) ---


def test_supervisor_skips_failed_monitor_and_routes_to_analysis():
    """monitor 실패 기록이 있으면 재진입하지 않고 analysis 로 부분 진행한다."""
    update = supervisor_node(
        {"incident": _incident(), "errors": [_failure("monitor")], "messages": []}
    )
    assert update["supervisor_decision"] == "analysis"


def test_supervisor_ends_with_escalation_after_analysis_failure():
    """analysis 실패 시 조치 없이 종료 — 부분 보고서 + 에스컬레이션 메시지."""
    update = supervisor_node(
        {
            "incident": _incident(),
            "monitoring": MonitoringResult(situation_summary="heap 98%"),
            "errors": [_failure("analysis")],
            "messages": [],
        }
    )
    assert update["supervisor_decision"] == "done"
    assert any("에스컬레이션" in m.content for m in update["messages"])


def test_supervisor_ends_after_action_failure():
    """action 실패 시 재진입 없이 종료한다 (분석 보고서까지는 확보된 상태)."""
    update = supervisor_node(
        {
            "incident": _incident(),
            "monitoring": MonitoringResult(situation_summary="heap 98%"),
            "analysis": _analysis("P1", 0.95),
            "errors": [_failure("action")],
            "messages": [],
        }
    )
    assert update["supervisor_decision"] == "done"


# --- analysis 부분 진행 (단위) ---


def test_analysis_node_handles_missing_monitoring(monkeypatch):
    """monitor 실패로 monitoring 이 없어도 analysis 는 그 사실을 명시하고 진행한다."""
    stub = _StubAnalysisAgent()
    monkeypatch.setattr(analysis_agent, "get_analysis_agent", _as_async_factory(stub))

    update = asyncio.run(
        analysis_agent.analysis_node(
            {"incident": _incident(), "monitoring": None, "messages": []}
        )
    )

    assert update["analysis"] is not None
    assert "모니터링" in stub.last_task.content  # 데이터 공백을 프롬프트에 명시

# --- 그래프 통합: error_handler 경로 ---


def test_monitor_failure_records_error_and_flows_to_partial_completion(monkeypatch):
    """monitor 노드 실패 → errors 기록 → analysis 부분 진행 → 우아한 종료."""
    monkeypatch.setattr(
        monitor_agent,
        "get_monitor_agent",
        lambda: _FailingAgent("[스텁] Prometheus 접속 불가"),
    )

    graph = build_graph()
    result = asyncio.run(graph.ainvoke({"incident": _incident(), "messages": []}))

    failures = result["errors"]
    assert [f.node for f in failures] == ["monitor"]
    assert failures[0].error_type == "RuntimeError"
    assert "Prometheus 접속 불가" in failures[0].message
    assert result.get("monitoring") is None
    assert result["analysis"] is not None  # 부분 진행 성공
    assert result["supervisor_decision"] == "done"


def test_analysis_failure_ends_with_partial_report(monkeypatch):
    """analysis 노드 실패 → errors 기록 + 확보된 monitoring 만으로 종료 (부분 보고서)."""
    monkeypatch.setattr(
        analysis_agent,
        "get_analysis_agent",
        _as_async_factory(_FailingAgent("[스텁] LLM 구조화 출력 파싱 실패")),
    )

    graph = build_graph()
    result = asyncio.run(graph.ainvoke({"incident": _incident(), "messages": []}))

    assert [f.node for f in result["errors"]] == ["analysis"]
    assert result["monitoring"] is not None  # 완료된 단계는 보존
    assert result.get("analysis") is None
    assert result.get("action") is None
    assert result["supervisor_decision"] == "done"
    assert any("에스컬레이션" in m.content for m in result["messages"])


# --- 노드별 타임아웃 ---


def test_node_timeout_records_failure_and_flows_on(monkeypatch):
    """타임아웃 초과 노드는 NodeTimeoutError 실패로 기록되고 부분 진행한다.

    타임아웃은 협조적 취소(asyncio) 기반 — sync 노드는 지원되지 않으므로
    에이전트 노드가 async 인 것 자체가 이 테스트의 전제다.
    """

    class _SlowMonitorAgent:
        def __init__(self) -> None:
            self.calls = 0

        async def ainvoke(self, payload: dict) -> dict:
            self.calls += 1
            await asyncio.sleep(5)
            return {"messages": [AIMessage(content="[스텁] 도달 불가")]}

    slow = _SlowMonitorAgent()
    monkeypatch.setattr(monitor_agent, "get_monitor_agent", lambda: slow)
    monkeypatch.setitem(supervisor_graph.NODE_TIMEOUTS, "monitor", 0.05)

    graph = build_graph()
    result = asyncio.run(graph.ainvoke({"incident": _incident(), "messages": []}))

    (failure,) = result["errors"]
    assert failure.node == "monitor"
    assert failure.error_type == "NodeTimeoutError"
    assert slow.calls == 1  # 타임아웃은 재시도 대상이 아니다 — 대기 반복 방지
    assert result["analysis"] is not None  # 부분 진행
    assert result["supervisor_decision"] == "done"


# --- 노드 재시도 정책 ---


class _FlakyMonitorAgent:
    """fail_remaining 회만큼 일시적 오류를 던진다 — 재시도 회복 검증용."""

    def __init__(self, fail_remaining: int) -> None:
        self.calls = 0
        self.fail_remaining = fail_remaining

    async def ainvoke(self, payload: dict) -> dict:
        self.calls += 1
        if self.fail_remaining > 0:
            self.fail_remaining -= 1
            raise ConnectionError("[스텁] 일시적 네트워크 오류")
        return {"messages": [AIMessage(content="[스텁] 상황 요약")]}


def _fast_retry_policy():
    """실 정책과 같은 retry_on 을 쓰되 대기만 줄인 테스트용 정책."""
    from langgraph.types import RetryPolicy

    return RetryPolicy(
        initial_interval=0.01,
        max_interval=0.02,
        jitter=False,
        retry_on=supervisor_graph.retry_on_transient,
    )


def test_retry_recovers_from_transient_error(monkeypatch):
    """일시적 오류(ConnectionError)는 노드 내 재시도로 회복 — 실패 기록 없이 완주한다."""
    flaky = _FlakyMonitorAgent(fail_remaining=1)
    monkeypatch.setattr(monitor_agent, "get_monitor_agent", lambda: flaky)
    monkeypatch.setattr(supervisor_graph, "AGENT_RETRY_POLICY", _fast_retry_policy())

    graph = build_graph()
    result = asyncio.run(graph.ainvoke({"incident": _incident(), "messages": []}))

    assert flaky.calls == 2  # 실패 1 + 재시도 성공 1
    assert result.get("errors") is None or result["errors"] == []
    assert result["monitoring"] is not None


def test_transient_error_exhausts_retries_then_records_failure(monkeypatch):
    """재시도 한도(3회)까지 계속 실패하면 그때 error_handler 로 기록된다."""
    flaky = _FlakyMonitorAgent(fail_remaining=10)
    monkeypatch.setattr(monitor_agent, "get_monitor_agent", lambda: flaky)
    monkeypatch.setattr(supervisor_graph, "AGENT_RETRY_POLICY", _fast_retry_policy())

    graph = build_graph()
    result = asyncio.run(graph.ainvoke({"incident": _incident(), "messages": []}))

    assert flaky.calls == 3  # max_attempts 기본값 (최초 시도 포함)
    (failure,) = result["errors"]
    assert failure.node == "monitor"
    assert failure.error_type == "ConnectionError"


def test_non_transient_error_fails_without_retry(monkeypatch):
    """프로그래밍 오류(RuntimeError)는 재시도해도 소용없다 — 즉시 실패 기록."""

    class _CountingFailingAgent(_FailingAgent):
        def __init__(self, message: str) -> None:
            super().__init__(message)
            self.calls = 0

        async def ainvoke(self, payload: dict, config: dict | None = None) -> dict:
            self.calls += 1
            return await super().ainvoke(payload, config)

    failing = _CountingFailingAgent("[스텁] 구조화 출력 파싱 실패")
    monkeypatch.setattr(monitor_agent, "get_monitor_agent", lambda: failing)
    monkeypatch.setattr(supervisor_graph, "AGENT_RETRY_POLICY", _fast_retry_policy())

    graph = build_graph()
    result = asyncio.run(graph.ainvoke({"incident": _incident(), "messages": []}))

    assert failing.calls == 1  # 재시도 없음
    assert [f.node for f in result["errors"]] == ["monitor"]


def test_retry_classifier_unwraps_exception_group():
    """MCP Streamable HTTP 의 연결 실패는 anyio TaskGroup 이 ExceptionGroup 으로 감싸서
    전파된다 (DAY 16 실측: ExceptionGroup(ConnectError)) — 풀어서 판정해야 재시도가 걸린다."""
    transient_group = ExceptionGroup("연결 실패", [ConnectionError("connection refused")])
    assert supervisor_graph.retry_on_transient(transient_group) is True


def test_retry_classifier_rejects_group_with_non_transient():
    """하나라도 비일시적 오류가 섞인 그룹은 재시도하지 않는다 — 프로그래밍 오류 반복 방지."""
    mixed = ExceptionGroup("혼합", [ConnectionError("일시적"), ValueError("프로그래밍 오류")])
    assert supervisor_graph.retry_on_transient(mixed) is False


def test_retry_classifier_unwraps_nested_group():
    """중첩 그룹(TaskGroup 안의 TaskGroup)도 말단까지 풀어서 판정한다."""
    nested = ExceptionGroup("바깥", [ExceptionGroup("안쪽", [ConnectionError("connection refused")])])
    assert supervisor_graph.retry_on_transient(nested) is True
