package stillframe42.controlplane.incident.service

import stillframe42.controlplane.incident.model.IncidentReport

/**
 * "보고서가 신규 저장됐다" 도메인 이벤트 (DAY 19) — 트랜잭션 안에서 발행되고,
 * 구독(알림)은 커밋 확정 후 실행된다 (@TransactionalEventListener AFTER_COMMIT).
 * 알림 같은 부수 작업을 트랜잭션 밖으로 내보내는 경계 — DB 커넥션이 HTTP 왕복에 묶이지 않는다.
 */
data class IncidentReportStored(val report: IncidentReport)
