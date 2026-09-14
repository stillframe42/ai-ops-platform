package stillframe42.controlplane.evaluation.service

import stillframe42.controlplane.evaluation.model.IncidentEvaluation

/**
 * "저품질 평가가 신규 저장됐다" 도메인 이벤트 — 트랜잭션 안에서 발행되고 구독(Slack 검토 요청)은 커밋 뒤 실행된다
 * (IncidentReportStored 와 같은 패턴). id 는 검토 API 링크용.
 */
data class LowQualityEvaluationStored(val id: Long, val evaluation: IncidentEvaluation)
