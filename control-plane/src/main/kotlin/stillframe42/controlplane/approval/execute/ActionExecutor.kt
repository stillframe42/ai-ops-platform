package stillframe42.controlplane.approval.execute

import stillframe42.controlplane.approval.model.ActionExecution

/**
 * 승인된 조치 1건의 실행 경계 (DAY 24, ADR-0005 — control-plane 대행).
 * 예외를 던지지 않는 계약: 성패는 반환값으로만 전달한다 — 실행 실패가
 * 결정 통보(decisions 발행)를 막으면 안 된다 (IncidentReportNotifier 와 같은 원칙).
 */
fun interface ActionExecutor {
    fun execute(action: String): ActionExecution
}
