package stillframe42.controlplane.incident.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import stillframe42.controlplane.common.jpa.AuditedEntity
import stillframe42.controlplane.incident.model.IncidentReport
import stillframe42.controlplane.incident.model.IncidentReportSummary

/**
 * incident_reports 영속 모델 (DAY 19 JPA 전환) — 스키마 소유는 Flyway V1~V2, 여기는 validate 만.
 * repository 구현 전용 — 서비스·컨트롤러는 model 의 데이터 클래스만 본다.
 * 갱신 대상 필드만 var — 재수신 갱신은 트랜잭션 안 dirty checking 으로 반영된다 (명시 save 없음).
 * 감사 4필드는 AuditedEntity 상속 — Spring Data Auditing 이 채운다 (JpaAuditingConfig).
 */
@Entity
@Table(name = "incident_reports")
class IncidentReportEntity(

    /** 자연 키 (발행 측 규약 id) — 신규/갱신 판정은 저장소가 선조회로 한다 (save 는 구분을 돌려주지 않음) */
    @Id
    @Column(name = "incident_id")
    val incidentId: String,

    @Column(nullable = false)
    val scenario: String,

    @Column(name = "alert_name")
    val alertName: String?,

    @Column(nullable = false)
    var status: String,

    var severity: String?,

    @Column(name = "root_cause")
    var rootCause: String?,

    var confidence: Double?,

    /** 보고서 원문 — String 이 곧 JSON 문서로 바인딩된다 (jsonb, Hibernate 6 SqlTypes.JSON) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    var report: String,

    @Column(name = "completed_at")
    var completedAt: Instant?,
) : AuditedEntity() {

    /** 재수신 갱신 규약 — 갱신 가능한 필드 집합을 한 곳이 소유한다 (upsert 의 UPDATE 절 대응) */
    fun applyUpdate(report: IncidentReport) {
        status = report.status
        severity = report.severity
        rootCause = report.rootCause
        confidence = report.confidence
        this.report = report.raw
        completedAt = report.completedAt
    }

    fun toSummary() = IncidentReportSummary(
        incidentId = incidentId,
        scenario = scenario,
        alertName = alertName,
        status = status,
        severity = severity,
        rootCause = rootCause,
        confidence = confidence,
        completedAt = completedAt,
        // NOT NULL 컬럼 — 영속화 전 접근은 설계상 없다 (조회 경로에서만 읽음)
        createdAt = createdAt!!,
        updatedAt = updatedAt!!,
    )

    companion object {
        fun from(report: IncidentReport) = IncidentReportEntity(
            incidentId = report.incidentId,
            scenario = report.scenario,
            alertName = report.alertName,
            status = report.status,
            severity = report.severity,
            rootCause = report.rootCause,
            confidence = report.confidence,
            report = report.raw,
            completedAt = report.completedAt,
        )
    }
}
