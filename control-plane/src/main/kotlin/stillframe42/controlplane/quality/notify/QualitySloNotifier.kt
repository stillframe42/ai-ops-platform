package stillframe42.controlplane.quality.notify


import stillframe42.controlplane.quality.model.QualitySloAlert
/** 품질 SLO 알림 발송 경계 — 실패해도 예외를 던지지 않는 계약 (IncidentReportNotifier 와 같다) */
fun interface QualitySloNotifier {
    fun notify(alert: QualitySloAlert)
}
