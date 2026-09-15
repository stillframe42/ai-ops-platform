"""EvaluationEventProcessor 계약 테스트 — aiokafka 무의존 (fake Judge/publisher 주입).

- 건너뜀(파싱 불가·미샘플·partial)은 정상 종료 — poison pill 이 커밋을 막으면 안 된다
- Judge 가 None 이면 발행 없이 정상 종료 (구현 전 경로)
- 인프라 실패(평가 발행 불가)만 예외 전파 — 커밋 보류 신호
- 샘플링·근거 판정이 소비 스팬 속성으로 남는다
"""

import asyncio
import json

import httpx
import pytest
from opentelemetry import trace
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from evaluation.config.otel import setup_telemetry
from evaluation.events.results_consumer import (
    ATTR_EVIDENCE,
    ATTR_SAMPLED,
    ATTR_SAMPLED_REASON,
    EvaluationEventProcessor,
)
from evaluation.evidence import EvidenceCollector
from evaluation.judge import DimensionScore, Evaluation, PendingJudge
from evaluation.sampling import Sampler

INCIDENT_ID = "inc-error-rate-surge-20260910040315-abc123"


def _report(**overrides) -> bytes:
    base = {
        "incident_id": INCIDENT_ID,
        "scenario": "error-rate-surge",
        "alert_name": "TargetAppHighErrorRate",
        "status": "completed",
        "analysis": {"severity": "P2", "root_cause_hypothesis": "chaos error-rate", "evidence": []},
        "action": {"actions": ["RESTART_APP"]},
        "approval": {"status": "approved"},
        "completed_at": "2026-09-10T04:10:00+00:00",
    }
    base.update(overrides)
    return json.dumps(base).encode()


class RecordingPublisher:
    def __init__(self, fail: bool = False):
        self.published: list[tuple[str, dict]] = []
        self.fail = fail

    async def __call__(self, key: str, payload: dict) -> None:
        if self.fail:
            raise RuntimeError("[스텁] Kafka 발행 실패")
        self.published.append((key, payload))


class FakeJudge:
    def __init__(self):
        self.calls: list[tuple[dict, dict | None]] = []

    async def evaluate(self, report: dict, evidence: dict | None) -> Evaluation | None:
        self.calls.append((report, evidence))
        return Evaluation(
            incident_id=report["incident_id"],
            scores={
                "faithfulness": DimensionScore(1.0, "일치"),
                "actionability": DimensionScore(0.4, "일반론 절반"),
                "severity_accuracy": DimensionScore(1.0, "P2 타당"),
            },
            failure_mode="C",
            judge_model="stub",
            prompt_version="test",
            evidence_available=evidence is not None,
            experiment_name=(report.get("experiment") or {}).get("name"),
            experiment_variant=(report.get("experiment") or {}).get("variant"),
        )


def _exporter() -> InMemorySpanExporter:
    setup_telemetry()
    exporter = InMemorySpanExporter()
    trace.get_tracer_provider().add_span_processor(SimpleSpanProcessor(exporter))
    return exporter


def test_malformed_message_is_skipped():
    processor = EvaluationEventProcessor(Sampler("experiment"), PendingJudge(), RecordingPublisher())
    assert asyncio.run(processor.process(b"{not json")) == "skipped:malformed"
    assert asyncio.run(processor.process(b"[1, 2]")) == "skipped:malformed"
    assert asyncio.run(processor.process(_report(incident_id=""))) == "skipped:no-incident-id"


def test_partial_report_is_skipped_without_judge_call():
    judge, publisher = FakeJudge(), RecordingPublisher()
    processor = EvaluationEventProcessor(Sampler("experiment"), judge, publisher)

    outcome = asyncio.run(processor.process(_report(status="partial")))

    assert outcome == "skipped:partial"
    assert judge.calls == [] and publisher.published == []


def test_unsampled_report_is_skipped_without_judge_call():
    judge = FakeJudge()
    processor = EvaluationEventProcessor(Sampler("production"), judge, RecordingPublisher())
    # P3 latency(warning)·승인 없음 → 10% 무작위 — 해시가 0.1 이상인 id 를 골라 미샘플 경로를 고정한다
    unsampled_id = next(
        f"inc-latency-surge-20260910040315-{i:06d}"
        for i in range(1000)
        if not Sampler("production")
        .decide({"incident_id": f"inc-latency-surge-20260910040315-{i:06d}", "status": "completed",
                 "alert_name": "TargetAppHighLatency", "analysis": {"severity": "P3"}, "approval": {"status": "skipped"}})
        .sampled
    )

    outcome = asyncio.run(processor.process(_report(
        incident_id=unsampled_id, alert_name="TargetAppHighLatency",
        analysis={"severity": "P3"}, approval={"status": "skipped"},
    )))

    assert outcome == "skipped:unsampled"
    assert judge.calls == []


def test_pending_judge_ends_without_publishing():
    publisher = RecordingPublisher()
    processor = EvaluationEventProcessor(Sampler("experiment"), PendingJudge(), publisher)

    assert asyncio.run(processor.process(_report())) == "sampled:judge-skipped"
    assert publisher.published == []


def test_evaluation_is_published_with_payload_contract():
    judge, publisher = FakeJudge(), RecordingPublisher()
    processor = EvaluationEventProcessor(Sampler("experiment"), judge, publisher)

    outcome = asyncio.run(processor.process(_report()))

    assert outcome == "evaluated"
    key, payload = publisher.published[0]
    assert key == INCIDENT_ID
    assert payload["scores"]["actionability"] == {"score": 0.4, "reason": "일반론 절반"}
    assert payload["failure_mode"] == "C" and payload["low_quality"] is True
    assert payload["evidence_available"] is False  # 재조회 비활성 (collector 미주입)
    assert payload["judge_model"] == "stub" and payload["prompt_version"] == "test"


def test_publish_failure_propagates_as_commit_hold_signal():
    processor = EvaluationEventProcessor(Sampler("experiment"), FakeJudge(), RecordingPublisher(fail=True))
    with pytest.raises(RuntimeError):
        asyncio.run(processor.process(_report()))


def test_evidence_failure_proceeds_without_evidence():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, text="prometheus down")

    collector = EvidenceCollector("http://prom", "http://loki", transport=httpx.MockTransport(handler))
    judge = FakeJudge()
    exporter = _exporter()
    processor = EvaluationEventProcessor(Sampler("experiment"), judge, RecordingPublisher(), evidence=collector)

    assert asyncio.run(processor.process(_report())) == "evaluated"

    assert judge.calls[0][1] is None
    span = next(s for s in exporter.get_finished_spans() if s.name == "ops.analysis.results process")
    assert span.attributes[ATTR_EVIDENCE] == "unavailable"


def test_consumer_span_carries_sampling_decision_and_incident_axis():
    exporter = _exporter()
    processor = EvaluationEventProcessor(Sampler("production"), PendingJudge(), RecordingPublisher())

    asyncio.run(processor.process(_report()))

    span = next(s for s in reversed(exporter.get_finished_spans()) if s.name == "ops.analysis.results process")
    assert span.attributes["incident.id"] == INCIDENT_ID
    assert span.attributes["gen_ai.conversation.id"] == INCIDENT_ID
    assert span.attributes[ATTR_SAMPLED] is True
    assert span.attributes[ATTR_SAMPLED_REASON] == "critical"  # TargetAppHighErrorRate = critical 규칙
    assert span.attributes[ATTR_EVIDENCE] == "disabled"
    assert span.attributes["messaging.destination.name"] == "ops.analysis.results"


def test_published_payload_carries_experiment_axis_from_report():
    judge, publisher = FakeJudge(), RecordingPublisher()
    processor = EvaluationEventProcessor(Sampler("experiment"), judge, publisher)

    asyncio.run(processor.process(_report(experiment={"name": "analysis-prompt-v2", "variant": "B"})))
    asyncio.run(processor.process(_report(experiment=None)))

    _, with_experiment = publisher.published[0]
    assert with_experiment["experiment_name"] == "analysis-prompt-v2" and with_experiment["experiment_variant"] == "B"
    _, without_experiment = publisher.published[1]
    assert without_experiment["experiment_name"] is None and without_experiment["experiment_variant"] is None
