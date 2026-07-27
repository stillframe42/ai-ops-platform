package stillframe42.controlplane.incident.notify

import stillframe42.controlplane.incident.model.IncidentReport

/**
 * 알림 발송 경계 (DAY 19) — 서비스는 채널(Slack)을 모른다. 실패해도 예외를 던지지 않는
 * 계약: 알림 실패가 보고서 저장(오프셋 커밋)을 되돌리면 안 된다.
 */
fun interface Notifier {
    fun notify(report: IncidentReport)
}
