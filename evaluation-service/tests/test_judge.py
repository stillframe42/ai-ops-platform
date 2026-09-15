"""Judge 출력 정규화 계약 — 앵커 스냅·실패 유형 규칙 (docs/quality-evaluation.md §2)."""

import pytest

from evaluation.judge import Evaluation, DimensionScore, VerdictError, parse_verdict


def _json(f=1.0, a=0.7, s=1.0, mode="없음") -> str:
    return (
        f'{{"faithfulness": {{"score": {f}, "reason": "f"}}, "actionability": {{"score": {a}, "reason": "a"}}, '
        f'"severity_accuracy": {{"score": {s}, "reason": "s"}}, "failure_mode": "{mode}"}}'
    )


def test_parses_anchor_scores_and_mode():
    verdict = parse_verdict("판정:\n```json\n" + _json(1.0, 0.4, 0.7, "C") + "\n```")
    assert {d: v.score for d, v in verdict.scores.items()} == {"faithfulness": 1.0, "actionability": 0.4, "severity_accuracy": 0.7}
    assert verdict.failure_mode == "C" and verdict.normalized is False


def test_continuous_score_snaps_to_nearest_anchor():
    verdict = parse_verdict(_json(0.85, 0.55, 0.2, "A"))
    assert verdict.scores["faithfulness"].score == 1.0  # 0.85 → 1.0 (0.15) vs 0.7 (0.15) — 동률은 앞 앵커
    assert verdict.scores["actionability"].score == 0.7
    assert verdict.scores["severity_accuracy"].score == 0.4
    assert verdict.normalized is True


def test_failure_mode_rule_is_enforced():
    # 전부 0.7 이상인데 유형을 냈으면 없음으로, 0.4 이하가 있는데 없음이면 가장 낮은 차원의 대표 유형으로
    assert parse_verdict(_json(1.0, 0.7, 1.0, "D")).failure_mode == "없음"
    assert parse_verdict(_json(1.0, 1.0, 0.4, "없음")).failure_mode == "D"
    assert parse_verdict(_json(0.4, 1.0, 1.0, "X")).failure_mode == "A"


@pytest.mark.parametrize("text", ["점수를 드릴 수 없습니다", '{"faithfulness": {"score": 1.0}}', '{"faithfulness": {"score": "높음"}, "actionability": {"score": 1}, "severity_accuracy": {"score": 1}}', "[1, 2]"])
def test_unreadable_verdict_raises(text):
    with pytest.raises(VerdictError):
        parse_verdict(text)


def test_payload_contract_carries_analysis_prompt_version():
    evaluation = Evaluation(
        incident_id="inc-x", scores={"faithfulness": DimensionScore(0.4, "r")}, failure_mode="A",
        judge_model="gpt-5.6-terra", prompt_version="v1", evidence_available=True, analysis_prompt_version="v2",
    )
    payload = evaluation.to_payload()
    assert payload["analysis_prompt_version"] == "v2" and payload["low_quality"] is True
    assert "judge_response_id" not in payload


def test_payload_contract_carries_experiment_axis_null_by_default():
    base = dict(
        incident_id="inc-x", scores={"faithfulness": DimensionScore(1.0, "r")}, failure_mode="없음",
        judge_model="gpt-5.6-terra", prompt_version="v1", evidence_available=False,
    )
    payload = Evaluation(**base).to_payload()
    assert payload["experiment_name"] is None and payload["experiment_variant"] is None

    payload = Evaluation(**base, experiment_name="analysis-prompt-v2", experiment_variant="B").to_payload()
    assert payload["experiment_name"] == "analysis-prompt-v2" and payload["experiment_variant"] == "B"
