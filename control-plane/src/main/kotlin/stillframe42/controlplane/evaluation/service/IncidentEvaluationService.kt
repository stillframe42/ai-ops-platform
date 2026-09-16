package stillframe42.controlplane.evaluation.service

import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import stillframe42.controlplane.evaluation.model.EvaluationReview
import stillframe42.controlplane.evaluation.model.ExperimentSummary
import stillframe42.controlplane.evaluation.model.ExperimentVariantSummary
import stillframe42.controlplane.evaluation.model.IncidentEvaluation
import stillframe42.controlplane.evaluation.model.IncidentEvaluationDetail
import stillframe42.controlplane.evaluation.model.IncidentEvaluationSummary
import stillframe42.controlplane.evaluation.model.ReviewOutcome
import stillframe42.controlplane.evaluation.model.ReviewStatus
import stillframe42.controlplane.evaluation.repository.IncidentEvaluationRepository

/**
 * 평가 결과 수신 처리 — 파싱 → 멱등 저장 → 저품질 신규 저장이면 이벤트 (AFTER_COMMIT 리스너가 Slack 검토 요청,
 * IncidentReportStored 와 같은 분리). 재수신 갱신은 이벤트를 내지 않는다 — 검토 요청 중복 방지.
 * 저장 예외는 그대로 전파 — 리스너 컨테이너의 재시도 경로 (AnalysisResultConsumer 주석).
 *
 * 리뷰: promoted 는 종결(골든셋 파일과 어긋나지 않게) — 그 외 상태에서는 검토를 다시 기록할 수 있다.
 * 실험 요약: 실험 1건은 수십 행이라 SQL GROUP BY 대신 전건을 읽어 Kotlin 에서 집계한다 — 저장소 경계를 요약 전용 쿼리로
 * 넓히지 않고, 저품질률·평균 규칙이 한 곳(여기)에 있다.
 */
@Service
class IncidentEvaluationService(
    private val incidentEvaluationRepository: IncidentEvaluationRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun ingest(payload: String) {
        val evaluation = IncidentEvaluation.parse(payload)
        if (evaluation == null) {
            logger.warn("평가 페이로드 파싱 실패 — 건너뜀 (본문 {}자)", payload.length)
            return
        }
        val result = incidentEvaluationRepository.upsert(evaluation)
        if (!result.isNew) {
            logger.info("평가 재수신 — {} 갱신만 수행 (judge={} prompt={})", evaluation.incidentId, evaluation.judgeModel, evaluation.promptVersion)
            return
        }
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
        if (evaluation.lowQuality) {
            applicationEventPublisher.publishEvent(LowQualityEvaluationStored(result.id, evaluation))
        }
    }

    fun findByIncidentId(incidentId: String): List<IncidentEvaluationDetail> =
        incidentEvaluationRepository.findByIncidentId(incidentId)

    fun findByReviewStatus(status: ReviewStatus, limit: Int): List<IncidentEvaluationDetail> =
        incidentEvaluationRepository.findByReviewStatus(status, limit)

    fun findByExperimentName(name: String): List<IncidentEvaluationDetail> =
        incidentEvaluationRepository.findByExperimentName(name)

    fun summarizeExperiment(name: String): ExperimentSummary {
        val variants = incidentEvaluationRepository.findByExperimentName(name)
            .map { it.summary }
            .filter { it.experimentVariant != null }
            .groupBy { it.experimentVariant!! }
            .map { (variant, rows) -> rows.summarizeVariant(variant) }
            .sortedBy { it.variant }
        return ExperimentSummary(experiment = name, variants = variants)
    }

    private fun List<IncidentEvaluationSummary>.summarizeVariant(variant: String) = ExperimentVariantSummary(
        variant = variant,
        n = size,
        faithfulnessAvg = map { it.faithfulness }.average(),
        actionabilityAvg = map { it.actionability }.average(),
        severityAccuracyAvg = map { it.severityAccuracy }.average(),
        lowQualityRate = count { it.lowQuality }.toDouble() / size,
    )

    @Transactional
    fun review(id: Long, review: EvaluationReview): ReviewOutcome {
        val current = incidentEvaluationRepository.findById(id) ?: return ReviewOutcome.NotFound
        if (current.summary.reviewStatus == ReviewStatus.PROMOTED) {
            return ReviewOutcome.AlreadyPromoted(current)
        }
        val updated = incidentEvaluationRepository.applyReview(id, review, clock.instant()) ?: return ReviewOutcome.NotFound
        logger.info(
            "평가 검토 — #{} {} {} → {} by {} (human F/A/S={} mode={})",
            id,
            current.summary.incidentId,
            current.summary.reviewStatus.wire,
            review.status.wire,
            review.reviewedBy,
            review.humanScores?.values?.joinToString("/") ?: "-",
            review.failureMode ?: "-",
        )
        return ReviewOutcome.Reviewed(updated)
    }
}
