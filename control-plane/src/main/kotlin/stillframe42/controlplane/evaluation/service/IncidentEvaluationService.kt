package stillframe42.controlplane.evaluation.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import stillframe42.controlplane.evaluation.model.IncidentEvaluation
import stillframe42.controlplane.evaluation.model.IncidentEvaluationDetail
import stillframe42.controlplane.evaluation.repository.IncidentEvaluationRepository

/**
 * 평가 결과 수신 처리 — 파싱 → 멱등 저장. 저품질은 pending_review 로 저장돼 사람 검토 큐의 입력이 된다
 * (Slack 알림·리뷰 API 는 검토 큐를 붙일 때 이 서비스에 이벤트로 잇는다 — IncidentReportStored 와 같은 AFTER_COMMIT 분리).
 * 저장 예외는 그대로 전파 — 리스너 컨테이너의 재시도 경로 (AnalysisResultConsumer 주석).
 */
@Service
class IncidentEvaluationService(private val incidentEvaluationRepository: IncidentEvaluationRepository) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun ingest(payload: String) {
        val evaluation = IncidentEvaluation.parse(payload)
        if (evaluation == null) {
            logger.warn("평가 페이로드 파싱 실패 — 건너뜀 (본문 {}자)", payload.length)
            return
        }
        val isNew = incidentEvaluationRepository.upsert(evaluation)
        if (isNew) {
            logger.info(
                "평가 저장 — {} F={} A={} S={} failure_mode={} review={} (judge={} prompt={})",
                evaluation.incidentId,
                evaluation.faithfulness,
                evaluation.actionability,
                evaluation.severityAccuracy,
                evaluation.failureMode,
                evaluation.initialReviewStatus().wire,
                evaluation.judgeModel,
                evaluation.promptVersion,
            )
        } else {
            logger.info("평가 재수신 — {} 갱신만 수행 (judge={} prompt={})", evaluation.incidentId, evaluation.judgeModel, evaluation.promptVersion)
        }
    }

    fun findByIncidentId(incidentId: String): List<IncidentEvaluationDetail> =
        incidentEvaluationRepository.findByIncidentId(incidentId)
}
