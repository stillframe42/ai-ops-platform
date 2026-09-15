"""분석 노드 재생 스크립트 계약 — 저장 보고서 → 재생 상태·페이로드 조립 (실 LLM·Kafka 무의존)."""

from scripts.replay_analysis import build_replay_state, build_replay_payload
from app.supervisor.state import AnalysisResult, ExperimentAssignment


def _stored() -> dict:
    return {
        "incident_id": "inc-latency-surge-20260915010203-abc123",
        "scenario": "latency-surge",
        "alert_name": "TargetAppHighLatency",
        "status": "completed",
        "created_at": "2026-09-15T01:05:00Z",
        "report": {
            "incident_id": "inc-latency-surge-20260915010203-abc123",
            "scenario": "latency-surge",
            "alert_name": "TargetAppHighLatency",
            "monitoring": {"situation_summary": "p95 3.4s", "evidences": ["histogram_quantile(...)"]},
            "analysis": {"root_cause_hypothesis": "원본 가설", "confidence": 0.8, "severity": "P2", "prompt_version": "v1"},
            "action": {"actions": ["RESTART_APP"]},
            "trace_ref": {"trace_id": "a" * 32, "span_id": "b" * 16},
        },
    }


def test_replay_state_reuses_stored_monitoring_and_forces_variant():
    assignment = ExperimentAssignment(name="analysis-model-haiku", variant="B", model_override=True)
    state = build_replay_state(_stored(), assignment)

    # 원본과 다른 incident_id — 원본 보고서·평가 행을 덮어쓰지 않는다
    assert state["incident"].id == "inc-latency-surge-20260915010203-abc123-replay-B"
    assert state["incident"].scenario == "latency-surge" and state["incident"].alert_name == "TargetAppHighLatency"
    assert state["incident"].occurred_at == "2026-09-15T01:05:00Z"
    assert state["monitoring"].situation_summary == "p95 3.4s"
    assert state["experiment"] == assignment


def test_replay_payload_marks_replay_and_carries_experiment():
    assignment = ExperimentAssignment(name="analysis-model-haiku", variant="B", model_override=True)
    update = {
        "analysis": AnalysisResult(root_cause_hypothesis="재생 가설", confidence=0.7, severity="P2"),
        "analysis_prompt_version": "v1",
    }
    payload = build_replay_payload(_stored(), assignment, update)

    assert payload["incident_id"] == "inc-latency-surge-20260915010203-abc123-replay-B"
    assert payload["status"] == "completed"
    assert payload["replay"] == {"of": "inc-latency-surge-20260915010203-abc123"}
    assert payload["experiment"] == {"name": "analysis-model-haiku", "variant": "B"}
    assert payload["analysis"]["root_cause_hypothesis"] == "재생 가설"
    assert payload["analysis"]["prompt_version"] == "v1"
    # 모니터링·조치는 원본 그대로 — 변인은 분석 노드 하나
    assert payload["monitoring"] == _stored()["report"]["monitoring"]
    assert payload["action"] == _stored()["report"]["action"]
    assert payload["trace_ref"] is None  # 재생은 원본 trace 가 아니다 — 평가 스팬이 엉뚱한 실행을 link 하지 않게
    assert payload["completed_at"]
