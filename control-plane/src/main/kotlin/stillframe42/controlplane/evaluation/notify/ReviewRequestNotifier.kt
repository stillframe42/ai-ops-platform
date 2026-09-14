package stillframe42.controlplane.evaluation.notify

import stillframe42.controlplane.evaluation.model.IncidentEvaluation

/** 저품질 검토 요청 발송 경계 — 서비스는 채널(Slack)을 모른다. 실패해도 예외를 던지지 않는 계약 (IncidentReportNotifier 와 같다) */
fun interface ReviewRequestNotifier {
    fun requestReview(id: Long, evaluation: IncidentEvaluation)
}
