package stillframe42.controlplane.evaluation.controller

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import stillframe42.controlplane.evaluation.model.EvaluationReview
import stillframe42.controlplane.evaluation.model.IncidentEvaluation
import stillframe42.controlplane.evaluation.model.IncidentEvaluationDetail
import stillframe42.controlplane.evaluation.model.IncidentEvaluationSummary
import stillframe42.controlplane.evaluation.model.ReviewStatus
import stillframe42.controlplane.evaluation.model.UpsertResult
import stillframe42.controlplane.evaluation.repository.IncidentEvaluationRepository
import stillframe42.controlplane.evaluation.service.IncidentEvaluationService

/** standalone MockMvc + fake 저장소 — 큐 조회 와이어·검토 요청 검증(400)·전이 상태 코드(404·409)만 검증 */
class EvaluationReviewControllerTest {

    private class FakeRepository(private val rows: MutableMap<Long, IncidentEvaluationDetail>) : IncidentEvaluationRepository {
        override fun upsert(evaluation: IncidentEvaluation): UpsertResult = UpsertResult(1, true)
        override fun findByIncidentId(incidentId: String): List<IncidentEvaluationDetail> = emptyList()
        override fun findById(id: Long): IncidentEvaluationDetail? = rows[id]
        override fun findByReviewStatus(status: ReviewStatus, limit: Int): List<IncidentEvaluationDetail> =
            rows.values.filter { it.summary.reviewStatus == status }.take(limit)
        override fun applyReview(id: Long, review: EvaluationReview, reviewedAt: Instant): IncidentEvaluationDetail? {
            val current = rows[id] ?: return null
            val updated = IncidentEvaluationDetail(
                current.summary.copy(
                    reviewStatus = review.status,
                    humanScores = review.humanScores,
                    humanFailureMode = review.failureMode,
                    reviewNote = review.note,
                    reviewedBy = review.reviewedBy,
                    reviewedAt = reviewedAt,
                ),
                current.evaluation,
            )
            rows[id] = updated
            return updated
        }
    }

    private fun summary(id: Long, status: ReviewStatus) = IncidentEvaluationSummary(
        id = id,
        incidentId = "inc-error-rate-surge-20260911110732-b68558",
        promptVersion = "v2",
        judgeModel = "gpt-5.6-terra",
        analysisPromptVersion = "v1",
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
    )

    private val raw = """{"scores": {"actionability": {"score": 0.4, "reason": "RESTART 배제"}}}"""

    private fun mvc(vararg rows: Pair<Long, ReviewStatus>) = MockMvcBuilders.standaloneSetup(
        EvaluationReviewController(
            IncidentEvaluationService(
                FakeRepository(rows.associate { (id, status) -> id to IncidentEvaluationDetail(summary(id, status), raw) }.toMutableMap()),
                ApplicationEventPublisher { },
            ),
        ),
    ).build()

    @Test
    fun `리뷰 큐는 상태별로 걸러 snake_case 로 돌려주고 모르는 상태는 400 이다`() {
        val mvc = mvc(1L to ReviewStatus.PENDING_REVIEW, 2L to ReviewStatus.PROMOTED)

        mvc.perform(get("/api/evaluations/review-queue"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].review_status").value("pending_review"))
            .andExpect(jsonPath("$[0].scores.actionability.reason").value("RESTART 배제"))
        mvc.perform(get("/api/evaluations/review-queue?status=promoted"))
            .andExpect(jsonPath("$[0].id").value(2))
        mvc.perform(get("/api/evaluations/review-queue?status=bogus")).andExpect(status().isBadRequest)
    }

    @Test
    fun `승격 검토는 사람 점수·유형·사유·검토자를 기록해 돌려준다`() {
        mvc(1L to ReviewStatus.PENDING_REVIEW).perform(
            post("/api/evaluations/1/review").contentType(MediaType.APPLICATION_JSON).content(
                """
                {"status": "promoted", "human_scores": {"faithfulness": 0.7, "actionability": 0.4, "severity_accuracy": 1.0},
                 "failure_mode": "C", "note": "RESTART 배제·CIRCUIT_BREAK 만", "reviewed_by": "sue"}
                """.trimIndent(),
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.review_status").value("promoted"))
            .andExpect(jsonPath("$.human_scores.actionability").value(0.4))
            .andExpect(jsonPath("$.human_failure_mode").value("C"))
            .andExpect(jsonPath("$.reviewed_by").value("sue"))
            .andExpect(jsonPath("$.reviewed_at").isString)
    }

    @Test
    fun `라벨 규약 위반은 400 - 앵커 밖 점수·승격에 점수 없음·모르는 상태`() {
        val mvc = mvc(1L to ReviewStatus.PENDING_REVIEW)
        val cases = listOf(
            """{"status": "promoted", "human_scores": {"faithfulness": 0.5, "actionability": 0.4, "severity_accuracy": 1.0}, "failure_mode": "C"}""",
            """{"status": "promoted"}""",
            """{"status": "approved"}""",
            """{"status": "reviewed", "human_scores": {"faithfulness": 1.0, "actionability": 1.0, "severity_accuracy": 1.0}, "failure_mode": "A"}""",
        )
        cases.forEach { body ->
            mvc.perform(post("/api/evaluations/1/review").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest)
        }
    }

    @Test
    fun `없는 평가는 404, 이미 승격된 평가는 409`() {
        val mvc = mvc(2L to ReviewStatus.PROMOTED)
        val dismiss = """{"status": "dismissed", "note": "Judge 오판"}"""

        mvc.perform(post("/api/evaluations/9/review").contentType(MediaType.APPLICATION_JSON).content(dismiss))
            .andExpect(status().isNotFound)
        mvc.perform(post("/api/evaluations/2/review").contentType(MediaType.APPLICATION_JSON).content(dismiss))
            .andExpect(status().isConflict)
        assertEquals(Unit, Unit)
    }
}
