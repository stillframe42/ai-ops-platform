package stillframe42.controlplane.evaluation.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import stillframe42.controlplane.common.jpa.AuditedEntity
import stillframe42.controlplane.evaluation.model.IncidentEvaluation
import stillframe42.controlplane.evaluation.model.IncidentEvaluationSummary
import stillframe42.controlplane.evaluation.model.ReviewStatus

/**
 * incident_evaluations 영속 모델 — 스키마 소유는 Flyway V5, 여기는 validate 만 (IncidentReportEntity 와 같은 관례).
 * 자연 키 (incident_id, prompt_version, judge_model) 의 신규/갱신 판정은 저장소가 선조회로 한다.
 * 재수신 갱신은 점수·판정·원문만 — review_status 는 사람 검토 상태라 재전달이 덮어쓰지 않는다.
 */
@Entity
@Table(name = "incident_evaluations")
class IncidentEvaluationEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "incident_id", nullable = false)
    val incidentId: String,

    @Column(name = "prompt_version", nullable = false)
    val promptVersion: String,

    @Column(name = "judge_model", nullable = false)
    val judgeModel: String,

    @Column(name = "analysis_prompt_version")
    var analysisPromptVersion: String?,

    @Column(nullable = false)
    var faithfulness: Double,

    @Column(nullable = false)
    var actionability: Double,

    @Column(name = "severity_accuracy", nullable = false)
    var severityAccuracy: Double,

    @Column(name = "failure_mode", nullable = false)
    var failureMode: String,

    @Column(name = "low_quality", nullable = false)
    var lowQuality: Boolean,

    @Column(name = "evidence_available", nullable = false)
    var evidenceAvailable: Boolean,

    /** 발행 페이로드 원문 — jsonb (report 컬럼과 같은 바인딩) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    var evaluation: String,

    @Column(name = "review_status", nullable = false)
    var reviewStatus: String,

    @Column(name = "evaluated_at", nullable = false)
    var evaluatedAt: Instant,
) : AuditedEntity() {

    fun applyUpdate(evaluation: IncidentEvaluation) {
        analysisPromptVersion = evaluation.analysisPromptVersion
        faithfulness = evaluation.faithfulness
        actionability = evaluation.actionability
        severityAccuracy = evaluation.severityAccuracy
        failureMode = evaluation.failureMode
        lowQuality = evaluation.lowQuality
        evidenceAvailable = evaluation.evidenceAvailable
        this.evaluation = evaluation.raw
        evaluatedAt = evaluation.evaluatedAt
    }

    fun toSummary() = IncidentEvaluationSummary(
        // IDENTITY 키 — 영속화 전 접근은 설계상 없다 (조회 경로에서만 읽음)
        id = id!!,
        incidentId = incidentId,
        promptVersion = promptVersion,
        judgeModel = judgeModel,
        analysisPromptVersion = analysisPromptVersion,
        faithfulness = faithfulness,
        actionability = actionability,
        severityAccuracy = severityAccuracy,
        failureMode = failureMode,
        lowQuality = lowQuality,
        evidenceAvailable = evidenceAvailable,
        reviewStatus = ReviewStatus.fromWire(reviewStatus),
        evaluatedAt = evaluatedAt,
        createdAt = createdAt!!,
        updatedAt = updatedAt!!,
    )

    companion object {
        fun from(evaluation: IncidentEvaluation) = IncidentEvaluationEntity(
            incidentId = evaluation.incidentId,
            promptVersion = evaluation.promptVersion,
            judgeModel = evaluation.judgeModel,
            analysisPromptVersion = evaluation.analysisPromptVersion,
            faithfulness = evaluation.faithfulness,
            actionability = evaluation.actionability,
            severityAccuracy = evaluation.severityAccuracy,
            failureMode = evaluation.failureMode,
            lowQuality = evaluation.lowQuality,
            evidenceAvailable = evaluation.evidenceAvailable,
            evaluation = evaluation.raw,
            reviewStatus = evaluation.initialReviewStatus().wire,
            evaluatedAt = evaluation.evaluatedAt,
        )
    }
}
