"""회복 확인 노드 — 조치 실행 후 트리거 조건 재평가 (DAY 24, ADR-0005 회복 확인 루프).

LLM 없는 규칙 판정: 회복의 정의가 "발화 중이던 Alert 의 해소"로 명확해 판단 여지가 없고
(scenarios.md "조치 후 트리거 조건 재평가" 그대로), 폴링 루프 안에 LLM 왕복을 두지 않는다.

approval → recovery 정적 엣지 — 실행이 없었던 결정(거부·만료·생략·실행 전원 실패)은
확인만 생략하고 supervisor 로 통과시킨다 (action → approval 과 같은 경유지 구조:
분기 조건을 라우팅 규칙에 더하지 않고 노드 내부 판단으로 유지, ADR-0008 정합).
"""

import asyncio
import json
from datetime import UTC, datetime

from langchain_core.messages import AIMessage

from app.supervisor.state import AIOpsState, RecoveryResult
from app.tools.prometheus_tools import get_active_alerts

# Alert 해소 폴링 예산 — Prometheus 평가 주기와 해소 지연을 고려한 상한. 초과 시 not_recovered
# 로 종결하고 보고서에 남긴다 (무한 대기 금지 — 그래프 노드 timeout 이 2차 방어).
# 수동 조치(manual) 항목이 있으면 사람 손 기준으로 예산을 늘린다 (2026-08-04 결정:
# RESTART_APP 자동 실행 제외 — 운영자 재시작을 기다리는 시간이 포함되므로)
RECOVERY_MAX_WAIT_SECONDS = 180.0
RECOVERY_MANUAL_WAIT_SECONDS = 600.0
RECOVERY_POLL_INTERVAL_SECONDS = 20.0


def _now_iso() -> str:
    return datetime.now(UTC).isoformat()


def _active_alert_names() -> set[str]:
    alerts = json.loads(get_active_alerts.invoke({}))
    return {alert.get("labels", {}).get("alertname", "") for alert in alerts}


async def recovery_node(state: AIOpsState) -> dict:
    approval = state.get("approval")
    executions = approval.executions if approval else []
    executed = [e.action for e in executions if e.ok and not e.manual]
    manual = [e.action for e in executions if e.manual]
    # 수동 항목도 확인 대상 — 운영자가 조치하면 Alert 해소로 관측된다 (누가 했는지는 판정에 불요)
    if approval is None or approval.status != "approved" or not (executed or manual):
        result = RecoveryResult(
            status="skipped",
            detail="실행·수동 안내된 조치 없음 — 회복 확인 생략",
            checked_at=_now_iso(),
        )
        return {
            "recovery": result,
            "messages": [AIMessage(content="[recovery] 생략 — 확인할 조치 없음")],
        }

    alert_name = state["incident"].alert_name
    if not alert_name:
        result = RecoveryResult(
            status="not_recovered",
            detail="alert_name 부재 — 트리거 조건 재평가 불가",
            checked_at=_now_iso(),
        )
        return {
            "recovery": result,
            "messages": [AIMessage(content="[recovery] 판정 불가 — alert_name 부재")],
        }

    budget = RECOVERY_MANUAL_WAIT_SECONDS if manual else RECOVERY_MAX_WAIT_SECONDS
    subject = " · ".join(
        part
        for part in (
            f"조치({', '.join(executed)})" if executed else "",
            f"수동 조치({', '.join(manual)})" if manual else "",
        )
        if part
    )
    loop = asyncio.get_running_loop()
    deadline = loop.time() + budget
    attempts = 0
    while True:
        attempts += 1
        try:
            # 동기 httpx 클라이언트 — 스레드로 내려 이벤트 루프 블로킹을 피한다 (tool 실행과 같은 방식)
            active = await asyncio.to_thread(_active_alert_names)
        except Exception as exc:  # noqa: BLE001 — 조회 실패는 노드 실패가 아니라 "확인 불가" 판정
            result = RecoveryResult(
                status="not_recovered",
                detail=f"Alert 조회 실패로 확인 불가 ({attempts}회 시도): {exc}",
                checked_at=_now_iso(),
                attempts=attempts,
            )
            break
        if alert_name not in active:
            result = RecoveryResult(
                status="recovered",
                detail=f"{subject} 후 Alert {alert_name} 해소 확인",
                checked_at=_now_iso(),
                attempts=attempts,
            )
            break
        if loop.time() >= deadline:
            waiting = " (수동 조치 대기)" if manual else ""
            result = RecoveryResult(
                status="not_recovered",
                detail=f"{int(budget)}s 안에 Alert {alert_name} 미해소{waiting} ({attempts}회 확인)",
                checked_at=_now_iso(),
                attempts=attempts,
            )
            break
        await asyncio.sleep(RECOVERY_POLL_INTERVAL_SECONDS)

    return {
        "recovery": result,
        "messages": [AIMessage(content=f"[recovery] {result.status} — {result.detail}")],
    }
