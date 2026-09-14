package stillframe42.controlplane.evaluation.repository

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import stillframe42.controlplane.evaluation.entity.IncidentEvaluationEntity

interface IncidentEvaluationEntityRepository : JpaRepository<IncidentEvaluationEntity, Long> {
    fun findByIncidentIdAndPromptVersionAndJudgeModel(
        incidentId: String,
        promptVersion: String,
        judgeModel: String,
    ): IncidentEvaluationEntity?

    fun findAllByIncidentIdOrderByEvaluatedAtDesc(incidentId: String): List<IncidentEvaluationEntity>

    fun findAllByReviewStatusOrderByEvaluatedAtDesc(reviewStatus: String, pageable: Pageable): List<IncidentEvaluationEntity>
}
