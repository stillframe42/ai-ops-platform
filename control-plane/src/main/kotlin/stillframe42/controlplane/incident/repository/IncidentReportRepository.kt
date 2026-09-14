package stillframe42.controlplane.incident.repository

import stillframe42.controlplane.incident.model.IncidentReport
import stillframe42.controlplane.incident.model.IncidentReportDetail
import stillframe42.controlplane.incident.model.IncidentReportSummary


import stillframe42.controlplane.messaging.EventPublisher
import stillframe42.controlplane.messaging.KafkaEventPublisher
/**
 * 인시던트 보고서 저장소 경계 — 서비스·컨트롤러는 이 인터페이스만 본다
 * (EventPublisher/KafkaEventPublisher 와 같은 fake 주입 관례).
 */
interface IncidentReportRepository {

    /** 멱등 저장 (at-least-once 재발행 짝). @return true = 신규 저장(첫 수신), false = 기존 갱신 */
    fun upsert(report: IncidentReport): Boolean

    /** 최신순 요약 목록 — report 원문(jsonb)은 제외 (목록에서 큰 컬럼을 나르지 않는다) */
    fun findRecent(limit: Int): List<IncidentReportSummary>

    fun findById(incidentId: String): IncidentReportDetail?
}
