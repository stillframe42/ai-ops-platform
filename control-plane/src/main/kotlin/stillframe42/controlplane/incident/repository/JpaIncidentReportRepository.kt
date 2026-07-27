package stillframe42.controlplane.incident.repository

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository
import stillframe42.controlplane.incident.entity.IncidentReportEntity
import stillframe42.controlplane.incident.model.IncidentReport
import stillframe42.controlplane.incident.model.IncidentReportDetail
import stillframe42.controlplane.incident.model.IncidentReportSummary

/** IncidentReportEntity 의 Spring Data 저장소 — 쿼리는 메서드 이름 파생 (SQL 문자열 없음). */
interface IncidentReportEntityRepository : JpaRepository<IncidentReportEntity, String> {
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): List<IncidentReportEntity>
}

/**
 * IncidentReportRepository 의 JPA 구현 (DAY 19, JdbcClient 초안에서 전환).
 * 트랜잭션 경계는 호출하는 서비스가 소유한다 — 저장소는 단일 호출의 정합성만 알 수 있고,
 * 유스케이스가 여러 저장소 호출로 커지면 묶음 정합성은 서비스 단위여야 한다.
 * 특히 upsert 의 dirty checking 갱신은 서비스 트랜잭션 안이라는 전제가 필요하다
 * (트랜잭션 없이 부르면 조회 즉시 준영속이 되어 갱신이 조용히 유실 — 노트 20260727).
 */
@Repository
class JpaIncidentReportRepository(
    private val entityRepository: IncidentReportEntityRepository,
) : IncidentReportRepository {

    override fun upsert(report: IncidentReport): Boolean {
        // 선조회 → 신규/갱신 분기. 경합 없음 — @KafkaListener 컨테이너는 기본 동시성 1(단일 스레드),
        // 같은 incident_id 는 같은 파티션이라 순서도 보장된다 (JdbcClient 구현과 같은 근거)
        val existing = entityRepository.findByIdOrNull(report.incidentId)
        if (existing == null) {
            entityRepository.save(IncidentReportEntity.from(report))
        } else {
            // 트랜잭션 안 dirty checking — 변경 감지로 UPDATE 가 나간다 (명시 save 불필요)
            existing.applyUpdate(report)
        }
        return existing == null
    }

    override fun findRecent(limit: Int): List<IncidentReportSummary> =
        entityRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit)).map { it.toSummary() }

    override fun findById(incidentId: String): IncidentReportDetail? =
        entityRepository.findByIdOrNull(incidentId)?.let { IncidentReportDetail(it.toSummary(), it.report) }
}
