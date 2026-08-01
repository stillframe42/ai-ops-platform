package stillframe42.controlplane.incident.repository

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import stillframe42.controlplane.incident.entity.IncidentReportEntity

/** IncidentReportEntity 의 Spring Data 저장소 — 쿼리는 메서드 이름 파생 (SQL 문자열 없음). */
interface IncidentReportEntityRepository : JpaRepository<IncidentReportEntity, String> {
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): List<IncidentReportEntity>
}
