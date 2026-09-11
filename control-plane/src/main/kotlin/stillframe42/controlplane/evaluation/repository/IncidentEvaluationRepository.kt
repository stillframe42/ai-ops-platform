package stillframe42.controlplane.evaluation.repository

import stillframe42.controlplane.evaluation.model.IncidentEvaluation
import stillframe42.controlplane.evaluation.model.IncidentEvaluationDetail

/** 평가 저장소 경계 — 서비스·컨트롤러는 이 인터페이스만 본다 (IncidentReportRepository 와 같은 fake 주입 관례) */
interface IncidentEvaluationRepository {

    /** 멱등 저장 (at-least-once 재발행 짝). @return true = 신규 저장, false = 기존 갱신 */
    fun upsert(evaluation: IncidentEvaluation): Boolean

    /** 인시던트 1건의 평가 전부 — 최신 평가 순 */
    fun findByIncidentId(incidentId: String): List<IncidentEvaluationDetail>
}
