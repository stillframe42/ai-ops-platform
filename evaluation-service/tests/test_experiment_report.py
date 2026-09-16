"""experiment_report.py 순수 함수 — 부트스트랩 CI(결정론)·사전 승자 기준 판정·Tempo trace 에서 분석 노드 지표 추출·마크다운 표."""

import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from experiment_report import (  # noqa: E402
    DEFAULT_PRICES,
    AnalysisMetrics,
    Sample,
    VariantStats,
    analysis_metrics_from_trace,
    bootstrap_diff_ci,
    decide,
    paired_control,
    render_markdown,
    summarize,
)


def test_bootstrap_diff_ci_is_deterministic_and_contains_point_estimate():
    treatment = [1.0, 1.0, 0.7, 1.0, 0.7, 1.0]
    control = [0.7, 0.4, 0.7, 1.0, 0.7, 0.4]

    first = bootstrap_diff_ci(treatment, control, iterations=500, seed=7)
    second = bootstrap_diff_ci(treatment, control, iterations=500, seed=7)

    assert first == second
    point = sum(treatment) / len(treatment) - sum(control) / len(control)
    assert first[0] <= point <= first[1]


def test_bootstrap_diff_ci_of_constant_samples_is_degenerate():
    assert bootstrap_diff_ci([1.0, 1.0, 1.0], [0.7, 0.7], iterations=100, seed=1) == pytest.approx((0.3, 0.3))


def _stats(variant, faithfulness, cost, n=12):
    return VariantStats(
        variant=variant,
        n=n,
        faithfulness=faithfulness,
        actionability=0.9,
        severity_accuracy=1.0,
        low_quality_rate=0.0,
        duration_s=30.0,
        input_tokens=100_000,
        output_tokens=1_000,
        cost_usd=cost,
        partial_rate=0.0,
    )


def test_decide_names_treatment_winner_when_faithfulness_gain_is_clear_and_cost_within_budget():
    verdict = decide(_stats("A", 0.80, 0.30), _stats("B", 0.90, 0.36), faithfulness_ci=(0.02, 0.18))

    assert verdict.winner == "B"
    assert "0.10" in verdict.reason


def test_decide_holds_when_ci_contains_zero():
    verdict = decide(_stats("A", 0.80, 0.30), _stats("B", 0.86, 0.30), faithfulness_ci=(-0.04, 0.16))

    assert verdict.winner is None
    assert "보류" in verdict.label


def test_decide_rejects_treatment_when_cost_exceeds_thirty_percent():
    verdict = decide(_stats("A", 0.80, 0.30), _stats("B", 0.95, 0.42), faithfulness_ci=(0.05, 0.25))

    assert verdict.winner == "A"
    assert "비용" in verdict.reason


def test_decide_keeps_control_when_treatment_is_clearly_worse():
    verdict = decide(_stats("A", 0.90, 0.30), _stats("B", 0.75, 0.30), faithfulness_ci=(-0.25, -0.05))

    assert verdict.winner == "A"


def test_decide_holds_when_gain_is_below_minimum_even_if_ci_excludes_zero():
    verdict = decide(_stats("A", 0.80, 0.30), _stats("B", 0.83, 0.30), faithfulness_ci=(0.01, 0.05))

    assert verdict.winner is None


def _span(name, span_id, parent, start_s, end_s, attrs):
    return {
        "name": name,
        "spanId": span_id,
        "parentSpanId": parent,
        "startTimeUnixNano": str(int(start_s * 1e9)),
        "endTimeUnixNano": str(int(end_s * 1e9)),
        "attributes": [{"key": k, "value": {"stringValue": str(v)}} for k, v in attrs.items()],
    }


def _trace(incident_id="inc-1"):
    # Tempo /api/traces 응답(OTLP JSON) 축약 — agent-service 배치만; 게이트웨이 배치의 chat 스팬은 세지 않는다 (같은 호출의 서버 측 기록)
    return {
        "batches": [
            {
                "resource": {"attributes": [{"key": "service.name", "value": {"stringValue": "agent-service"}}]},
                "scopeSpans": [
                    {
                        "spans": [
                            _span("invoke_workflow incident-response", "w", "", 0.0, 60.0, {"incident.id": incident_id}),
                            _span("invoke_agent analysis", "a", "w", 10.0, 40.0, {"incident.id": incident_id, "aiops.experiment.variant": "B"}),
                            _span("chat default", "c1", "a", 11.0, 13.0, {"gen_ai.response.model": "claude-sonnet-5", "gen_ai.usage.input_tokens": 10000, "gen_ai.usage.output_tokens": 100}),
                            _span("chat default", "c2", "a", 14.0, 16.0, {"gen_ai.response.model": "claude-sonnet-5", "gen_ai.usage.input_tokens": 20000, "gen_ai.usage.output_tokens": 200}),
                            _span("chat default", "c3", "w", 2.0, 4.0, {"gen_ai.response.model": "claude-haiku-4-5-20251001", "gen_ai.usage.input_tokens": 5000, "gen_ai.usage.output_tokens": 50}),
                        ]
                    }
                ],
            },
            {
                "resource": {"attributes": [{"key": "service.name", "value": {"stringValue": "llm-gateway"}}]},
                "scopeSpans": [{"spans": [_span("chat claude-sonnet-5", "g1", "x", 11.0, 13.0, {"gen_ai.usage.input_tokens": 10000, "gen_ai.usage.output_tokens": 100})]}],
            },
        ]
    }


def test_analysis_metrics_from_trace_sums_only_chat_spans_under_the_analysis_node():
    metrics = analysis_metrics_from_trace(_trace(), DEFAULT_PRICES)

    assert metrics == AnalysisMetrics(
        duration_s=30.0,
        llm_calls=2,
        input_tokens=30000,
        output_tokens=300,
        cost_usd=pytest.approx(30000 / 1e6 * 3.0 + 300 / 1e6 * 15.0),
    )


def test_analysis_metrics_from_trace_without_analysis_node_is_none():
    trace = _trace()
    trace["batches"][0]["scopeSpans"][0]["spans"] = [s for s in trace["batches"][0]["scopeSpans"][0]["spans"] if s["name"] != "invoke_agent analysis"]

    assert analysis_metrics_from_trace(trace, DEFAULT_PRICES) is None


def test_analysis_metrics_prices_match_by_model_prefix():
    trace = _trace()
    for span in trace["batches"][0]["scopeSpans"][0]["spans"]:
        for attr in span["attributes"]:
            if attr["key"] == "gen_ai.response.model" and attr["value"]["stringValue"] == "claude-sonnet-5":
                attr["value"]["stringValue"] = "claude-haiku-4-5-20251001"

    metrics = analysis_metrics_from_trace(trace, DEFAULT_PRICES)

    assert metrics.cost_usd == pytest.approx(30000 / 1e6 * 1.0 + 300 / 1e6 * 5.0)


def _sample(variant, faithfulness, cost, partial=False):
    return Sample(
        incident_id=f"inc-{variant}-{faithfulness}-{cost}",
        variant=variant,
        faithfulness=faithfulness,
        actionability=1.0,
        severity_accuracy=0.7,
        low_quality=faithfulness < 0.7,
        partial=partial,
        metrics=AnalysisMetrics(duration_s=20.0, llm_calls=3, input_tokens=1000, output_tokens=100, cost_usd=cost),
    )


def test_summarize_groups_by_variant_and_averages():
    stats = summarize([_sample("A", 1.0, 0.2), _sample("A", 0.4, 0.4, partial=True), _sample("B", 0.7, 0.3)])

    assert [s.variant for s in stats] == ["A", "B"]
    assert stats[0].n == 2
    assert stats[0].faithfulness == pytest.approx(0.7)
    assert stats[0].cost_usd == pytest.approx(0.3)
    assert stats[0].low_quality_rate == pytest.approx(0.5)
    assert stats[0].partial_rate == pytest.approx(0.5)
    assert stats[1].n == 1


def test_render_markdown_has_variant_table_verdict_and_per_incident_rows():
    samples = [_sample("A", 1.0, 0.2), _sample("B", 0.7, 0.3)]
    stats = summarize(samples)
    verdict = decide(stats[0], stats[1], faithfulness_ci=(-0.5, -0.1))

    markdown = render_markdown("analysis-prompt-v2", samples, stats, faithfulness_ci=(-0.5, -0.1), cost_ci=(0.0, 0.2), verdict=verdict)

    assert "| variant | n |" in markdown
    assert "| A | 1 |" in markdown
    assert "판정" in markdown and verdict.label in markdown
    assert "inc-A-1.0-0.2" in markdown
    assert json.dumps  # 마크다운 안에 JSON 은 없다 — 표만


def _evaluation(incident_id, experiment, variant, faithfulness, evaluated_at="2026-09-16T03:00:00Z"):
    return {
        "incident_id": incident_id,
        "experiment_name": experiment,
        "experiment_variant": variant,
        "evaluated_at": evaluated_at,
        "low_quality": faithfulness < 0.7,
        "scores": {"faithfulness": {"score": faithfulness}, "actionability": {"score": 1.0}, "severity_accuracy": {"score": 1.0}},
    }


def test_paired_control_takes_original_incident_rows_from_another_experiment():
    # 실험 2: 처리군은 재생(<원본>-replay-B), control 은 실험 1 의 A(v1·sonnet) 원본 — 같은 인시던트끼리 짝
    replays = [_evaluation("inc-1-replay-B", "analysis-model-haiku", "B", 0.7), _evaluation("inc-2-replay-B", "analysis-model-haiku", "B", 1.0)]
    originals = [
        _evaluation("inc-1", "analysis-prompt-v2", "A", 1.0),
        _evaluation("inc-2", "analysis-prompt-v2", "B", 1.0),
        _evaluation("inc-3", "analysis-prompt-v2", "A", 0.4),
    ]

    control = paired_control(replays, originals, control_variant="A", suffix="-replay-B")

    assert [c["incident_id"] for c in control] == ["inc-1"]
    assert control[0]["experiment_variant"] == "A"


def test_paired_control_skips_replays_whose_original_is_not_control_or_is_missing():
    replays = [_evaluation("inc-9-replay-B", "analysis-model-haiku", "B", 0.7), _evaluation("inc-8-replay-B", "analysis-model-haiku", "B", 0.7)]
    originals = [_evaluation("inc-9", "analysis-prompt-v2", "B", 1.0)]

    assert paired_control(replays, originals, control_variant="A", suffix="-replay-B") == []
