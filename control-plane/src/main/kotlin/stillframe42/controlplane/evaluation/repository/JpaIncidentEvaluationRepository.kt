package stillframe42.controlplane.evaluation.repository

import java.time.Instant
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import stillframe42.controlplane.evaluation.entity.IncidentEvaluationEntity
import stillframe42.controlplane.evaluation.model.EvaluationReview
import stillframe42.controlplane.evaluation.model.IncidentEvaluation
import stillframe42.controlplane.evaluation.model.IncidentEvaluationDetail
import stillframe42.controlplane.evaluation.model.ReviewStatus
import stillframe42.controlplane.evaluation.model.UpsertResult

/**
 * IncidentEvaluationRepository 의 JPA 구현. 트랜잭션 경계는 서비스 소유 — upsert·applyReview 의 dirty checking 갱신은
 * 서비스 트랜잭션 안이라는 전제가 필요하다 (JpaIncidentReportRepository 와 같은 근거).
 */
@Repository
class JpaIncidentEvaluationRepository(
    private val incidentEvaluationEntityRepository: IncidentEvaluationEntityRepository,
) : IncidentEvaluationRepository {

    override fun upsert(evaluation: IncidentEvaluation): UpsertResult {
        // 선조회 → 신규/갱신. 경합 없음 — 리스너 컨테이너 동시성 1, 같은 incident_id 는 같은 파티션
        val existing = incidentEvaluationEntityRepository.findByIncidentIdAndPromptVersionAndJudgeModel(
            evaluation.incidentId,
            evaluation.promptVersion,
            evaluation.judgeModel,
        )
        if (existing == null) {
            val saved = incidentEvaluationEntityRepository.save(IncidentEvaluationEntity.from(evaluation))
            return UpsertResult(id = saved.id!!, isNew = true)
        }
        existing.applyUpdate(evaluation)
        return UpsertResult(id = existing.id!!, isNew = false)
    }

    override fun findByIncidentId(incidentId: String): List<IncidentEvaluationDetail> =
        incidentEvaluationEntityRepository.findAllByIncidentIdOrderByEvaluatedAtDesc(incidentId).map { it.toDetail() }

    override fun findById(id: Long): IncidentEvaluationDetail? =
        incidentEvaluationEntityRepository.findById(id).orElse(null)?.toDetail()

    override fun findByReviewStatus(status: ReviewStatus, limit: Int): List<IncidentEvaluationDetail> =
        incidentEvaluationEntityRepository.findAllByReviewStatusOrderByEvaluatedAtDesc(status.wire, PageRequest.of(0, limit))
            .map { it.toDetail() }

    override fun applyReview(id: Long, review: EvaluationReview, reviewedAt: Instant): IncidentEvaluationDetail? {
        val entity = incidentEvaluationEntityRepository.findById(id).orElse(null) ?: return null
        entity.applyReview(review, reviewedAt)
        return entity.toDetail()
    }

    private fun IncidentEvaluationEntity.toDetail() = IncidentEvaluationDetail(toSummary(), evaluation)
}
