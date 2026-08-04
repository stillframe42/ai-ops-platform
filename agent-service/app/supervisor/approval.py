"""승인 노드 — 조치 실행 전 human-in-the-loop 대기 (ADR-0005·0006).

action → approval 정적 엣지라 이 노드에 오는 계획은 전부 P1/P2 다 — P3 는 supervisor
조기 종료로 action 자체에 도달하지 않는다 (ADR-0008 정합을 라우팅 규칙 수정 없이 구조로 확보).

interrupt 페이로드가 곧 승인 요청서다. ops.actions.pending 발행은 컨슈머 계층 몫으로 남긴다 —
그래프는 Kafka 를 모른다 (노드가 발행까지 하면 재개 시 노드 재실행마다 부수 효과가 반복된다).
재개 입력(ops.actions.decisions 페이로드)은 소비 측이 Command(resume=...) 로 넣는다.

주의: 이 노드에는 error_handler·retry_policy 를 붙이지 않는다 — interrupt 는 예외 전파로
동작하므로, 가로채면 승인 대기가 실패 기록으로 오인된다.
"""

from datetime import UTC, datetime

from langchain_core.messages import AIMessage
from langgraph.types import interrupt

from app.supervisor.state import ActionExecution, AIOpsState, ApprovalDecision

# 사람 승인 없이 지나가도 되는 조치 — 알림뿐이라 인프라 변경이 없다
NO_APPROVAL_ACTIONS = frozenset({"NOTIFY_ONLY"})

DECISION_STATUSES = ("approved", "rejected", "expired")


def build_approval_request(state: AIOpsState) -> dict:
    """ops.actions.pending 페이로드 겸 Slack 승인 카드 재료 (ADR-0006 스펙)."""
    incident = state["incident"]
    analysis = state.get("analysis")
    plan = state["action"]
    return {
        "incident_id": incident.id,
        "scenario": incident.scenario,
        "alert_name": incident.alert_name,
        "severity": analysis.severity if analysis else None,
        "confidence": analysis.confidence if analysis else None,
        "root_cause_hypothesis": analysis.root_cause_hypothesis if analysis else "",
        "actions": list(plan.actions),
        "rationale": plan.rationale,
        "expected_effect": plan.expected_effect,
        "risk": plan.risk,
        "requested_at": datetime.now(UTC).isoformat(),
    }


def _normalize_executions(raw: object) -> list[ActionExecution]:
    """decisions 페이로드의 execution 배열 — 형식이 어긋난 항목은 버린다 (실행 결과는 참고 정보)."""
    if not isinstance(raw, list):
        return []
    return [
        ActionExecution(
            action=str(item.get("action") or ""),
            ok=bool(item.get("ok", False)),
            detail=str(item.get("detail") or ""),
            manual=bool(item.get("manual", False)),
        )
        for item in raw
        if isinstance(item, dict)
    ]


def _normalize(raw: object) -> ApprovalDecision:
    if isinstance(raw, dict) and raw.get("status") in DECISION_STATUSES:
        return ApprovalDecision(
            status=raw["status"],
            decided_by=str(raw.get("decided_by") or ""),
            note=str(raw.get("note") or ""),
            executions=_normalize_executions(raw.get("execution")),
            executed_at=str(raw.get("executed_at") or ""),
        )
    # 알 수 없는 페이로드는 안전 측 거부 — 승인 없이 실행에 도달하는 경로를 만들지 않는다
    return ApprovalDecision(status="rejected", note=f"알 수 없는 결정 페이로드: {raw!r}"[:300])


def approval_node(state: AIOpsState) -> dict:
    plan = state.get("action")
    if plan is None or not [a for a in plan.actions if a not in NO_APPROVAL_ACTIONS]:
        return {
            "approval": ApprovalDecision(status="skipped"),
            "messages": [AIMessage(content="[approval] 실행 조치 없음 — 승인 생략")],
        }
    decision = _normalize(interrupt(build_approval_request(state)))
    suffix = f" (by {decision.decided_by})" if decision.decided_by else ""
    return {
        "approval": decision,
        "messages": [AIMessage(content=f"[approval] 결정: {decision.status}{suffix}")],
    }
