"""GatewayJudge 계약 — 게이트웨이 호출 규약·판정·텔레메트리 (httpx MockTransport, 토큰 발급 포함 스텁)."""

import asyncio
import json

import httpx
import pytest
from opentelemetry import trace
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from evaluation.config.otel_evaluation import (
    ATTR_DIMENSION,
    ATTR_FAILURE_MODE,
    ATTR_JUDGE_OUTCOME,
    EVENT_NAME,
    METRIC_JUDGE_CALLS,
    METRIC_SCORE,
    METRIC_VERDICTS,
)
from evaluation.config.settings import Settings
from evaluation.judge_gateway import GatewayJudge
from tests.conftest import LOG_EXPORTER, METRIC_READER

INCIDENT_ID = "inc-latency-surge-20260910061900-abc123"
TRACE_ID, SPAN_ID = "bea594ac1234567890abcdef12345678", "1234567890abcdef"


def _settings() -> Settings:
    return Settings(llm_base_url="http://gateway:8090/v1", auth_token_url="http://auth:8091/oauth2/token", auth_client_secret="s")


def _report() -> dict:
    return {
        "incident_id": INCIDENT_ID,
        "scenario": "latency-surge",
        "alert_name": "TargetAppHighLatency",
        "status": "completed",
        "analysis": {
            "severity": "P2",
            "root_cause_hypothesis": "메모리 압박이 지연 원인",
            "evidence": ["OOM 로그 06:07"],
            "suggested_actions": ["heap dump"],
            "confidence": 0.9,
            "prompt_version": "v1",
        },
        "action": {"actions": ["RESTART_APP"], "rationale": "완화"},
        "trace_ref": {"trace_id": TRACE_ID, "span_id": SPAN_ID},
    }


def _verdict(f=0.4, a=0.7, s=1.0, mode="B") -> str:
    return json.dumps(
        {
            "faithfulness": {"score": f, "reason": "이전 회차 OOM 을 현재 원인으로"},
            "actionability": {"score": a, "reason": "RESTART 는 타당"},
            "severity_accuracy": {"score": s, "reason": "P2 타당"},
            "failure_mode": mode,
        },
        ensure_ascii=False,
    )


class Gateway:
    """토큰 발급 + 채팅 응답 스텁 — 요청을 기록한다."""

    def __init__(self, content: str | None = None, status: int = 200):
        self.content, self.status = content, status
        self.chat_requests: list[httpx.Request] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/oauth2/token"):
            return httpx.Response(200, json={"access_token": "tok", "expires_in": 900})
        self.chat_requests.append(request)
        if self.status != 200:
            return httpx.Response(self.status, json={"error": "blocked"})
        return httpx.Response(
            200,
            json={
                "id": "chatcmpl-1",
                "model": "gpt-5.6-terra",
                "choices": [{"finish_reason": "stop", "message": {"role": "assistant", "content": self.content}}],
                "usage": {"prompt_tokens": 1200, "completion_tokens": 90},
            },
            headers={"X-Gateway-Cache": "BYPASS", "X-Gateway-Guardrail": "clean"},
        )


def _exporter() -> InMemorySpanExporter:
    exporter = InMemorySpanExporter()
    trace.get_tracer_provider().add_span_processor(SimpleSpanProcessor(exporter))
    return exporter


def _evaluate(gateway: Gateway, report: dict | None = None, evidence: dict | None = None):
    judge = GatewayJudge(_settings(), transport=httpx.MockTransport(gateway))
    try:
        return asyncio.run(judge.evaluate(report or _report(), evidence))
    finally:
        asyncio.run(judge.aclose())


def test_calls_gateway_with_judge_task_no_cache_and_no_temperature():
    gateway = Gateway(_verdict())

    evaluation = _evaluate(gateway)

    request = gateway.chat_requests[0]
    assert request.url == "http://gateway:8090/v1/chat/completions"
    assert request.headers["X-Task-Type"] == "evaluation-judge"
    assert request.headers["X-Cache-Control"] == "no-cache"
    assert request.headers["Authorization"] == "Bearer tok"
    body = json.loads(request.content)
    assert "temperature" not in body and body["model"] == "default"
    assert body["messages"][0]["role"] == "system" and "비신뢰 콘텐츠 규칙" in body["messages"][0]["content"]
    user = body["messages"][1]["content"]
    assert '<untrusted_content source="incident-report">' in user and "confidence" not in user
    assert "## 주입 사실" not in user  # 온라인 경로는 정답 없음

    assert evaluation is not None
    assert evaluation.scores["faithfulness"].score == 0.4 and evaluation.failure_mode == "B"
    assert evaluation.judge_model == "gpt-5.6-terra" and evaluation.prompt_version == "v1"
    assert evaluation.analysis_prompt_version == "v1" and evaluation.evidence_available is False
    assert evaluation.judge_response_id == "chatcmpl-1" and evaluation.low_quality is True


def test_ground_truth_is_optional_input_for_golden_measurement():
    gateway = Gateway(_verdict())
    judge = GatewayJudge(_settings(), transport=httpx.MockTransport(gateway))
    asyncio.run(judge.evaluate(_report(), None, ground_truth="POST /chaos/latency?ms=3500"))
    assert "## 주입 사실 (정답)\nPOST /chaos/latency?ms=3500" in json.loads(gateway.chat_requests[0].content)["messages"][1]["content"]


def test_unparseable_verdict_is_skipped_with_error_type():
    exporter = _exporter()

    assert _evaluate(Gateway("채점할 수 없습니다.")) is None

    span = next(s for s in exporter.get_finished_spans() if s.name == "evaluate incident-report")
    assert span.attributes["error.type"] == "VerdictError"
    assert span.status.status_code.name == "ERROR"


def test_gateway_rejection_is_skipped_with_http_error_type():
    exporter = _exporter()

    assert _evaluate(Gateway(status=400)) is None

    span = next(s for s in exporter.get_finished_spans() if s.name == "evaluate incident-report")
    assert span.attributes["error.type"] == "http.400"


def test_evaluation_span_links_workflow_and_chat_span_carries_genai_attributes():
    exporter = _exporter()

    _evaluate(Gateway(_verdict()))

    spans = exporter.get_finished_spans()
    evaluate = next(s for s in spans if s.name == "evaluate incident-report")
    assert evaluate.attributes["aiops.operation"] == "evaluate"
    assert evaluate.attributes["gen_ai.conversation.id"] == INCIDENT_ID
    assert evaluate.attributes["aiops.evaluation.failure_mode"] == "B"
    assert evaluate.attributes["aiops.evaluation.low_quality"] is True
    link = evaluate.links[0]
    assert format(link.context.trace_id, "032x") == TRACE_ID and format(link.context.span_id, "016x") == SPAN_ID
    assert link.attributes["aiops.link.reason"] == "evaluation-of"

    chat = next(s for s in spans if s.name == "chat default")
    assert chat.parent.span_id == evaluate.context.span_id
    assert chat.attributes["gen_ai.operation.name"] == "chat"
    assert chat.attributes["gen_ai.response.model"] == "gpt-5.6-terra"
    assert chat.attributes["gen_ai.response.id"] == "chatcmpl-1"
    assert chat.attributes["gen_ai.usage.input_tokens"] == 1200
    assert chat.attributes["gen_ai.conversation.id"] == INCIDENT_ID  # 상속
    # 게이트웨이 판정 헤더(gateway.*) 승격은 httpx 계측 스팬의 훅 — MockTransport 는 계측 대상이 아니라 여기서는 못 본다 (compose 실측)

    events = [e for e in evaluate.events if e.name == EVENT_NAME]
    assert [e.attributes["gen_ai.evaluation.name"] for e in events] == ["faithfulness", "actionability", "severity_accuracy"]
    assert events[0].attributes["gen_ai.evaluation.score.label"] == "fail"
    assert events[0].attributes["gen_ai.response.id"] == "chatcmpl-1"


def test_evaluation_emits_log_events_and_score_histogram():
    LOG_EXPORTER.clear()

    _evaluate(Gateway(_verdict()))

    records = [r.log_record for r in LOG_EXPORTER.get_finished_logs() if r.log_record.event_name == EVENT_NAME]
    assert len(records) == 3
    faithfulness = next(r for r in records if r.attributes["gen_ai.evaluation.name"] == "faithfulness")
    assert faithfulness.attributes["gen_ai.evaluation.score.value"] == 0.4
    assert faithfulness.attributes["gen_ai.conversation.id"] == INCIDENT_ID
    assert faithfulness.trace_id != 0  # 평가 스팬 컨텍스트가 실린다

    data = METRIC_READER.get_metrics_data()
    metric = next(
        m for rm in data.resource_metrics for sm in rm.scope_metrics for m in sm.metrics if m.name == METRIC_SCORE
    )
    points = {p.attributes[ATTR_DIMENSION]: p for p in metric.data.data_points if p.attributes["aiops.prompt.version"] == "v1"}
    assert list(points["faithfulness"].explicit_bounds) == [0.0, 0.4, 0.7, 1.0]
    assert points["faithfulness"].attributes["aiops.evaluation.severity"] == "P2"
    assert points["faithfulness"].attributes["aiops.evaluation.judge_prompt_version"] == "v1"


def test_unknown_prompt_version_fails_fast():
    with pytest.raises(FileNotFoundError):
        GatewayJudge(_settings(), prompt_version="v9")


def _count(name: str, **attrs) -> int:
    """카운터 합 — 아직 기록이 없으면(리더가 None 반환) 0. 단독 실행(-k)과 전체 실행 모두에서 같은 기준."""
    data = METRIC_READER.get_metrics_data()
    if data is None:
        return 0
    found = [m for rm in data.resource_metrics for sm in rm.scope_metrics for m in sm.metrics if m.name == name]
    if not found:
        return 0
    return sum(
        int(p.value)
        for p in found[-1].data.data_points
        if all(p.attributes.get(k) == v for k, v in attrs.items())
    )


def test_counters_cover_verdict_failure_mode_and_judge_call_outcome():
    """대시보드·SLO 룰의 축 — 판정 카운터는 failure_mode, 호출 카운터는 outcome(ok|error). 실패 호출은 판정 카운터를 올리지 않는다."""
    ok_before = _count(METRIC_JUDGE_CALLS, **{ATTR_JUDGE_OUTCOME: "ok"})
    err_before = _count(METRIC_JUDGE_CALLS, **{ATTR_JUDGE_OUTCOME: "error", "error.type": "VerdictError"})
    verdicts_before = _count(METRIC_VERDICTS, **{ATTR_FAILURE_MODE: "D"})

    assert _evaluate(Gateway(_verdict(f=1.0, a=1.0, s=0.4, mode="D"))) is not None
    assert _evaluate(Gateway("판정 불가")) is None

    assert _count(METRIC_JUDGE_CALLS, **{ATTR_JUDGE_OUTCOME: "ok"}) == ok_before + 1
    assert _count(METRIC_JUDGE_CALLS, **{ATTR_JUDGE_OUTCOME: "error", "error.type": "VerdictError"}) == err_before + 1
    assert _count(METRIC_VERDICTS, **{ATTR_FAILURE_MODE: "D"}) == verdicts_before + 1
