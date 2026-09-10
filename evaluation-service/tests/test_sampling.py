"""샘플링 계약 테스트 (docs/quality-evaluation.md §3) — P1 항상 · 보조 조건 100% · 결정론 · 비율 오차 · partial 정책."""

import pytest

from evaluation.sampling import PROFILES, Sampler, hash_fraction


def _report(**overrides) -> dict:
    base = {
        "incident_id": "inc-latency-surge-20260910040315-abc123",
        "scenario": "latency-surge",
        "alert_name": "TargetAppHighLatency",  # severity=warning 규칙 — 보조 조건 비해당
        "status": "completed",
        "analysis": {"severity": "P2", "root_cause_hypothesis": "…"},
        "approval": {"status": "skipped"},
    }
    base.update(overrides)
    return base


def test_p1_is_always_sampled_in_every_profile():
    for profile in PROFILES:
        decision = Sampler(profile).decide(_report(analysis={"severity": "P1"}))
        assert decision.sampled and decision.reason == "p1" and decision.rate == 1.0


def test_critical_alert_and_requested_approval_are_always_sampled():
    sampler = Sampler("production")
    critical = sampler.decide(_report(alert_name="TargetAppHighErrorRate", analysis={"severity": "P3"}))
    assert critical.sampled and critical.reason == "critical"
    approved = sampler.decide(_report(approval={"status": "rejected"}, analysis={"severity": "P3"}))
    assert approved.sampled and approved.reason == "approval"


def test_same_incident_id_gives_same_decision():
    sampler = Sampler("production")
    first = sampler.decide(_report())
    for _ in range(20):
        assert sampler.decide(_report()) == first


def test_hash_fraction_is_in_unit_interval_and_stable():
    value = hash_fraction("inc-latency-surge-20260910040315-abc123")
    assert 0.0 <= value < 1.0
    assert hash_fraction("inc-latency-surge-20260910040315-abc123") == value


@pytest.mark.parametrize(("severity", "expected"), [("P2", 0.30), ("P3", 0.10), (None, 0.15)])
def test_production_ratio_within_two_percent_over_ten_thousand(severity, expected):
    sampler = Sampler("production")
    sampled = 0
    for i in range(10_000):
        decision = sampler.decide(_report(incident_id=f"inc-latency-surge-20260910040315-{i:06d}", analysis={"severity": severity}))
        assert decision.rate == expected
        sampled += decision.sampled
    assert abs(sampled / 10_000 - expected) <= 0.02


def test_experiment_profile_samples_everything():
    sampler = Sampler("experiment")
    for i in range(500):
        decision = sampler.decide(_report(incident_id=f"inc-latency-surge-20260910040315-{i:06d}", analysis={"severity": "P3"}))
        assert decision.sampled and decision.reason == "random" and decision.rate == 1.0


def test_partial_report_is_skipped_even_when_severity_is_p1():
    decision = Sampler("experiment").decide(_report(status="partial", analysis={"severity": "P1"}))
    assert not decision.sampled and decision.reason == "skipped" and decision.detail == "partial"


def test_missing_analysis_block_is_skipped_as_partial():
    decision = Sampler("experiment").decide(_report(analysis=None))
    assert not decision.sampled and decision.detail == "partial"


def test_unknown_profile_is_rejected():
    with pytest.raises(ValueError):
        Sampler("staging")  # type: ignore[arg-type]
