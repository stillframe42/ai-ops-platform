package stillframe42.controlplane.approval.repository

import org.springframework.data.jpa.repository.JpaRepository
import stillframe42.controlplane.approval.entity.ActionApprovalEntity

/** ActionApprovalEntity 의 Spring Data 저장소 — 쿼리는 메서드 이름 파생 (SQL 문자열 없음). */
interface ActionApprovalEntityRepository : JpaRepository<ActionApprovalEntity, Long> {
    fun findByIncidentIdAndStatus(incidentId: String, status: String): ActionApprovalEntity?
    fun findFirstByIncidentIdOrderByIdDesc(incidentId: String): ActionApprovalEntity?
}
