package stillframe42.controlplane.evaluation.repository

import org.springframework.stereotype.Repository
import stillframe42.controlplane.evaluation.entity.IncidentEvaluationEntity
import stillframe42.controlplane.evaluation.model.IncidentEvaluation
import stillframe42.controlplane.evaluation.model.IncidentEvaluationDetail

/**
 * IncidentEvaluationRepository 의 JPA 구현. 트랜잭션 경계는 서비스 소유 — upsert 의 dirty checking 갱신은
 * 서비스 트랜잭션 안이라는 전제가 필요하다 (JpaIncidentReportRepository 와 같은 근거).
 */
@Repository
class JpaIncidentEvaluationRepository(
    private val incidentEvaluationEntityRepository: IncidentEvaluationEntityRepository,
) : IncidentEvaluationRepository {

    override fun upsert(evaluation: IncidentEvaluation): Boolean {
        // 선조회 → 신규/갱신. 경합 없음 — 리스너 컨테이너 동시성 1, 같은 incident_id 는 같은 파티션
        val existing = incidentEvaluationEntityRepository.findByIncidentIdAndPromptVersionAndJudgeModel(
            evaluation.incidentId,
            evaluation.promptVersion,
            evaluation.judgeModel,
        )
        if (existing == null) {
            incidentEvaluationEntityRepository.save(IncidentEvaluationEntity.from(evaluation))
        } else {
            existing.applyUpdate(evaluation)
        }
        return existing == null
    }

    override fun findByIncidentId(incidentId: String): List<IncidentEvaluationDetail> =
        incidentEvaluationEntityRepository.findAllByIncidentIdOrderByEvaluatedAtDesc(incidentId)
            .map { IncidentEvaluationDetail(it.toSummary(), it.evaluation) }
}
