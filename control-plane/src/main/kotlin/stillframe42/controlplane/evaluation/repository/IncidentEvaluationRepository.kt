package stillframe42.controlplane.evaluation.repository

import java.time.Instant
import stillframe42.controlplane.evaluation.model.EvaluationReview
import stillframe42.controlplane.evaluation.model.IncidentEvaluation
import stillframe42.controlplane.evaluation.model.IncidentEvaluationDetail
import stillframe42.controlplane.evaluation.model.ReviewStatus
import stillframe42.controlplane.evaluation.model.UpsertResult

/** 평가 저장소 경계 — 서비스·컨트롤러는 이 인터페이스만 본다 (IncidentReportRepository 와 같은 fake 주입 관례) */
interface IncidentEvaluationRepository {

    /** 멱등 저장 (at-least-once 재발행 짝) — isNew=false 는 기존 갱신 */
    fun upsert(evaluation: IncidentEvaluation): UpsertResult

    /** 인시던트 1건의 평가 전부 — 최신 평가 순 */
    fun findByIncidentId(incidentId: String): List<IncidentEvaluationDetail>

    fun findById(id: Long): IncidentEvaluationDetail?

    /** 리뷰 큐 — 상태별 최신 평가 순 (V5 idx_incident_evaluations_review) */
    fun findByReviewStatus(status: ReviewStatus, limit: Int): List<IncidentEvaluationDetail>

    /** 사람 검토 반영. @return 갱신된 행, 없는 id 는 null. 전이 가능 여부 판정은 서비스 몫 */
    fun applyReview(id: Long, review: EvaluationReview, reviewedAt: Instant): IncidentEvaluationDetail?
}
