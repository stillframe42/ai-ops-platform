package stillframe42.controlplane.evaluation.service

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.springframework.context.ApplicationEventPublisher
import stillframe42.controlplane.evaluation.model.EvaluationReview
import stillframe42.controlplane.evaluation.model.IncidentEvaluation
import stillframe42.controlplane.evaluation.model.IncidentEvaluationDetail
import stillframe42.controlplane.evaluation.model.IncidentEvaluationSummary
import stillframe42.controlplane.evaluation.model.ReviewOutcome
import stillframe42.controlplane.evaluation.model.ReviewStatus
import stillframe42.controlplane.evaluation.model.UpsertResult
import stillframe42.controlplane.evaluation.repository.IncidentEvaluationRepository

/** 단위 테스트 경계 — Kafka·DB 무의존, fake 저장소. 멱등 저장·건너뜀·저품질 이벤트·검토 전이·실험 집계 규약만 검증한다 */
class IncidentEvaluationServiceTest {

    private class RecordingRepository(
        private val isNew: Boolean,
        private val stored: IncidentEvaluationDetail? = null,
        private val experimentRows: List<IncidentEvaluationDetail> = emptyList(),
    ) : IncidentEvaluationRepository {
        val upserted = mutableListOf<IncidentEvaluation>()
        val reviews = mutableListOf<Pair<Long, EvaluationReview>>()

        override fun upsert(evaluation: IncidentEvaluation): UpsertResult {
            upserted += evaluation
            return UpsertResult(id = 7, isNew = isNew)
        }
        override fun findByIncidentId(incidentId: String): List<IncidentEvaluationDetail> = emptyList()
        override fun findById(id: Long): IncidentEvaluationDetail? = stored?.takeIf { it.summary.id == id }
        override fun findByReviewStatus(status: ReviewStatus, limit: Int): List<IncidentEvaluationDetail> =
            listOfNotNull(stored).filter { it.summary.reviewStatus == status }
        override fun findByExperimentName(experimentName: String): List<IncidentEvaluationDetail> =
            experimentRows.filter { it.summary.experimentName == experimentName }
        override fun applyReview(id: Long, review: EvaluationReview, reviewedAt: Instant): IncidentEvaluationDetail? {
            reviews += id to review
            return stored?.let { IncidentEvaluationDetail(it.summary.copy(reviewStatus = review.status, reviewedAt = reviewedAt), it.evaluation) }
        }
    }

    private val events = mutableListOf<Any>()
    private val clock = Clock.fixed(Instant.parse("2026-09-13T10:00:00Z"), ZoneOffset.UTC)

    private fun service(repository: IncidentEvaluationRepository) =
        IncidentEvaluationService(repository, ApplicationEventPublisher { events += it }, clock)

    private fun payload(lowQuality: Boolean) = """
        {"incident_id": "inc-x", "scores": {"faithfulness": {"score": 1.0, "reason": ""}, "actionability": {"score": 1.0, "reason": ""},
         "severity_accuracy": {"score": ${if (lowQuality) 0.4 else 1.0}, "reason": "과대"}}, "failure_mode": "${if (lowQuality) "D" else "없음"}",
         "low_quality": $lowQuality, "judge_model": "gpt-5.6-terra", "prompt_version": "v1", "evidence_available": false,
         "evaluated_at": "2026-09-11T02:00:00+00:00"}
    """.trimIndent()

    private fun experimentRow(id: Long, variant: String?, faithfulness: Double, actionability: Double, severityAccuracy: Double) =
        IncidentEvaluationDetail(
            detail(ReviewStatus.NOT_REQUIRED).summary.copy(
                id = id,
                incidentId = "inc-$id",
                experimentName = "analysis-prompt-v2",
                experimentVariant = variant,
                faithfulness = faithfulness,
                actionability = actionability,
                severityAccuracy = severityAccuracy,
                lowQuality = minOf(faithfulness, actionability, severityAccuracy) < 0.7,
            ),
            "{}",
        )

    private fun detail(status: ReviewStatus) = IncidentEvaluationDetail(
        IncidentEvaluationSummary(
            id = 7,
            incidentId = "inc-x",
            promptVersion = "v2",
            judgeModel = "gpt-5.6-terra",
            analysisPromptVersion = "v1",
            experimentName = null,
            experimentVariant = null,
            faithfulness = 0.7,
            actionability = 0.4,
            severityAccuracy = 1.0,
            failureMode = "B",
            lowQuality = true,
            evidenceAvailable = true,
            reviewStatus = status,
            evaluatedAt = Instant.parse("2026-09-11T11:08:31Z"),
            createdAt = Instant.parse("2026-09-11T11:08:32Z"),
            updatedAt = Instant.parse("2026-09-11T11:08:32Z"),
        ),
        "{}",
    )

    @Test
    fun `신규·재수신 모두 upsert 로 저장한다`() {
        val fresh = RecordingRepository(isNew = true)
        service(fresh).ingest(payload(lowQuality = true))
        assertEquals(1, fresh.upserted.size)
        assertEquals("D", fresh.upserted.single().failureMode)

        val again = RecordingRepository(isNew = false)
        service(again).ingest(payload(lowQuality = true))
        assertEquals(1, again.upserted.size)
    }

    @Test
    fun `저품질 신규 저장만 검토 요청 이벤트를 낸다 - 재수신·정상 품질은 없음`() {
        service(RecordingRepository(isNew = true)).ingest(payload(lowQuality = true))
        val event = assertIs<LowQualityEvaluationStored>(events.single())
        assertEquals(7, event.id)
        assertEquals("inc-x", event.evaluation.incidentId)

        events.clear()
        service(RecordingRepository(isNew = false)).ingest(payload(lowQuality = true))
        service(RecordingRepository(isNew = true)).ingest(payload(lowQuality = false))
        assertEquals(0, events.size)
    }

    @Test
    fun `파싱 불가 페이로드는 저장 없이 건너뛴다`() {
        val repository = RecordingRepository(isNew = true)
        service(repository).ingest("{\"incident_id\": \"inc-x\"}")
        assertEquals(0, repository.upserted.size)
    }

    @Test
    fun `검토는 pending_review 에서 promoted 로 전이하고 검토 시각을 기록한다`() {
        val repository = RecordingRepository(isNew = true, stored = detail(ReviewStatus.PENDING_REVIEW))
        val review = EvaluationReview(
            status = ReviewStatus.PROMOTED,
            humanScores = mapOf("faithfulness" to 0.7, "actionability" to 0.4, "severity_accuracy" to 1.0),
            failureMode = "C",
            note = "RESTART 배제·CIRCUIT_BREAK 만",
            reviewedBy = "sue",
        )

        val outcome = assertIs<ReviewOutcome.Reviewed>(service(repository).review(7, review))

        assertEquals(ReviewStatus.PROMOTED, outcome.detail.summary.reviewStatus)
        assertEquals(clock.instant(), outcome.detail.summary.reviewedAt)
        assertEquals(7L to review, repository.reviews.single())
    }

    @Test
    fun `promoted 는 종결 - 다시 검토하면 충돌, 없는 id 는 미존재`() {
        val dismiss = EvaluationReview(ReviewStatus.DISMISSED, null, null, "Judge 오판", "sue")

        val promoted = RecordingRepository(isNew = true, stored = detail(ReviewStatus.PROMOTED))
        assertIs<ReviewOutcome.AlreadyPromoted>(service(promoted).review(7, dismiss))
        assertEquals(0, promoted.reviews.size)

        assertIs<ReviewOutcome.NotFound>(service(RecordingRepository(isNew = true)).review(99, dismiss))
    }

    @Test
    fun `실험 요약은 variant 별 건수·차원 평균·저품질률을 이름순으로 내고 variant 없는 행은 뺀다`() {
        val repository = RecordingRepository(
            isNew = true,
            experimentRows = listOf(
                experimentRow(1, "B", faithfulness = 1.0, actionability = 0.7, severityAccuracy = 1.0),
                experimentRow(2, "A", faithfulness = 0.4, actionability = 1.0, severityAccuracy = 0.7),
                experimentRow(3, "B", faithfulness = 0.7, actionability = 1.0, severityAccuracy = 0.4),
                experimentRow(4, null, faithfulness = 0.0, actionability = 0.0, severityAccuracy = 0.0),
            ),
        )

        val summary = service(repository).summarizeExperiment("analysis-prompt-v2")

        assertEquals("analysis-prompt-v2", summary.experiment)
        assertEquals(listOf("A", "B"), summary.variants.map { it.variant })
        val a = summary.variants[0]
        assertEquals(1, a.n)
        assertEquals(0.4, a.faithfulnessAvg)
        assertEquals(1.0, a.actionabilityAvg)
        assertEquals(0.7, a.severityAccuracyAvg)
        assertEquals(1.0, a.lowQualityRate)
        val b = summary.variants[1]
        assertEquals(2, b.n)
        assertEquals(0.85, b.faithfulnessAvg)
        assertEquals(0.85, b.actionabilityAvg)
        assertEquals(0.7, b.severityAccuracyAvg)
        assertEquals(0.5, b.lowQualityRate)
    }

    @Test
    fun `평가가 없는 실험은 빈 variant 목록이다`() {
        val summary = service(RecordingRepository(isNew = true)).summarizeExperiment("unknown")
        assertEquals("unknown", summary.experiment)
        assertEquals(emptyList(), summary.variants)
    }
}
