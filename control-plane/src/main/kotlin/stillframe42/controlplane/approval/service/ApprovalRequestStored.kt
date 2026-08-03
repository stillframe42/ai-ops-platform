package stillframe42.controlplane.approval.service

import stillframe42.controlplane.approval.model.ActionApprovalRequest

/**
 * 신규 승인 요청 저장 완료 이벤트 (DAY 23) — AFTER_COMMIT 리스너가 Slack 승인 카드를
 * 발송한다 (IncidentReportStored 와 같은 패턴: 저장 롤백 시 카드도 나가지 않는다).
 * 재수신(기존 pending 유지)은 발행하지 않는다 — 카드 중복 방지.
 */
data class ApprovalRequestStored(val request: ActionApprovalRequest)
