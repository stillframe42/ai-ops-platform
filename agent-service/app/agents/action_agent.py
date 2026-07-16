"""실행 에이전트 — 분석 결과를 근거로 조치 계획(ActionPlan)을 생성한다.

계획 생성까지만이 이번 주 범위 — 실제 실행은 4주차 human-in-the-loop 승인 이후 (ADR-0005).
DAY 8 골격: 더미 계획만 반환. DAY 11 에서 조치 카탈로그 기반으로 교체.
"""

from langchain_core.messages import AIMessage

from app.supervisor.state import ActionPlan, AIOpsState


def action_node(state: AIOpsState) -> dict:
    plan = ActionPlan(
        actions=["NOTIFY_ONLY"],
        rationale="[더미] 조치 계획 생성은 DAY 11 구현",
    )
    return {
        "action": plan,
        "messages": [AIMessage(content=f"[action] 계획: {plan.actions} — {plan.rationale}")],
    }
