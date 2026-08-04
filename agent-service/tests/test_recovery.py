"""회복 확인 노드 테스트 — 규칙 판정 계약 (DAY 24, ADR-0005 회복 확인 루프).

LLM 무의존 노드라 직접 호출로 검증한다. Alert 조회는 monkeypatch 로 대체 —
판정 규칙(생략/회복/미회복/확인 불가)과 폴링 계약만 본다.
"""

import asyncio

from app.supervisor import recovery
from app.supervisor.state import ActionExecution, ApprovalDecision, IncidentInfo

ALERT = "TargetAppHighErrorRate"


def _state(approval: ApprovalDecision | None, alert_name: str | None = ALERT) -> dict:
    return {
        "incident": IncidentInfo(
            id="inc-rec-001",
            scenario="error-rate-surge",
            alert_name=alert_name,
            summary="5xx 급증",
            occurred_at="2026-08-04T00:00:00+00:00",
        ),
        "approval": approval,
    }


def _approved(executions: list[ActionExecution]) -> ApprovalDecision:
    return ApprovalDecision(status="approved", decided_by="U0123ABC", executions=executions)


def test_rejected_decision_skips_check() -> None:
    result = asyncio.run(recovery.recovery_node(_state(ApprovalDecision(status="rejected"))))

    assert result["recovery"].status == "skipped"


def test_all_failed_executions_skip_check() -> None:
    """성공한 조치가 없으면 재평가할 변화도 없다 — 생략이 정직하다."""
    decision = _approved([ActionExecution(action="RESTART_APP", ok=False, detail="시간 초과")])

    result = asyncio.run(recovery.recovery_node(_state(decision)))

    assert result["recovery"].status == "skipped"


def test_recovered_when_alert_cleared(monkeypatch) -> None:
    monkeypatch.setattr(recovery, "_active_alert_names", lambda: {"OtherAlert"})
    decision = _approved([ActionExecution(action="RESTART_APP", ok=True, detail="완료")])

    result = asyncio.run(recovery.recovery_node(_state(decision)))

    assert result["recovery"].status == "recovered"
    assert result["recovery"].attempts == 1
    assert ALERT in result["recovery"].detail


def test_polls_until_alert_clears(monkeypatch) -> None:
    """첫 확인에서 미해소면 간격을 두고 재확인한다 — 해소 시점의 attempts 가 남는다."""
    checks = iter([{ALERT}, set()])
    monkeypatch.setattr(recovery, "_active_alert_names", lambda: next(checks))
    monkeypatch.setattr(recovery, "RECOVERY_POLL_INTERVAL_SECONDS", 0.0)
    decision = _approved([ActionExecution(action="CIRCUIT_BREAK", ok=True, detail="완료")])

    result = asyncio.run(recovery.recovery_node(_state(decision)))

    assert result["recovery"].status == "recovered"
    assert result["recovery"].attempts == 2


def test_manual_action_is_checked_too(monkeypatch) -> None:
    """수동 안내 항목도 확인 대상 — 운영자가 조치하면 Alert 해소로 관측된다 (2026-08-04 결정)."""
    monkeypatch.setattr(recovery, "_active_alert_names", lambda: set())
    decision = _approved(
        [ActionExecution(action="RESTART_APP", ok=True, detail="운영자 직접 실행 대상", manual=True)]
    )

    result = asyncio.run(recovery.recovery_node(_state(decision)))

    assert result["recovery"].status == "recovered"
    assert "수동 조치(RESTART_APP)" in result["recovery"].detail


def test_manual_budget_exhaustion_says_waiting(monkeypatch) -> None:
    """수동 조치가 늦으면 '수동 조치 대기'로 정직하게 종결한다 — 안정화 보고 불가의 수용 형태."""
    monkeypatch.setattr(recovery, "RECOVERY_MANUAL_WAIT_SECONDS", 0.0)
    monkeypatch.setattr(recovery, "_active_alert_names", lambda: {ALERT})
    decision = _approved(
        [ActionExecution(action="RESTART_APP", ok=True, detail="운영자 직접 실행 대상", manual=True)]
    )

    result = asyncio.run(recovery.recovery_node(_state(decision)))

    assert result["recovery"].status == "not_recovered"
    assert "수동 조치 대기" in result["recovery"].detail


def test_not_recovered_when_budget_exhausted(monkeypatch) -> None:
    monkeypatch.setattr(recovery, "RECOVERY_MAX_WAIT_SECONDS", 0.0)
    monkeypatch.setattr(recovery, "_active_alert_names", lambda: {ALERT})
    decision = _approved([ActionExecution(action="RESTART_APP", ok=True, detail="완료")])

    result = asyncio.run(recovery.recovery_node(_state(decision)))

    assert result["recovery"].status == "not_recovered"
    assert "미해소" in result["recovery"].detail


def test_alert_query_failure_is_not_recovered(monkeypatch) -> None:
    """조회 실패는 노드 실패가 아니라 '확인 불가' 판정 — 보고서에 사유가 남는다."""

    def boom() -> set[str]:
        raise RuntimeError("prometheus down")

    monkeypatch.setattr(recovery, "_active_alert_names", boom)
    decision = _approved([ActionExecution(action="RESTART_APP", ok=True, detail="완료")])

    result = asyncio.run(recovery.recovery_node(_state(decision)))

    assert result["recovery"].status == "not_recovered"
    assert "확인 불가" in result["recovery"].detail


def test_missing_alert_name_cannot_evaluate() -> None:
    decision = _approved([ActionExecution(action="RESTART_APP", ok=True, detail="완료")])

    result = asyncio.run(recovery.recovery_node(_state(decision, alert_name=None)))

    assert result["recovery"].status == "not_recovered"
    assert "재평가 불가" in result["recovery"].detail
