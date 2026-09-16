package stillframe42.controlplane.evaluation.controller

import java.time.Instant
import kotlin.test.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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

/** standalone MockMvc + fake 저장소 — 실험 평가 목록은 인시던트 평가 응답과 같은 와이어, 저장소 순서(최신 평가 순)를 그대로 */
class ExperimentEvaluationsControllerTest {

    private class FakeRepository(private val rows: List<IncidentEvaluationDetail>) : IncidentEvaluationRepository {
        override fun upsert(evaluation: IncidentEvaluation): UpsertResult = UpsertResult(1, true)
        override fun findByIncidentId(incidentId: String): List<IncidentEvaluationDetail> = emptyList()
        override fun findById(id: Long): IncidentEvaluationDetail? = null
        override fun findByReviewStatus(status: ReviewStatus, limit: Int): List<IncidentEvaluationDetail> = emptyList()
        override fun findByExperimentName(experimentName: String): List<IncidentEvaluationDetail> =
            rows.filter { it.summary.experimentName == experimentName }
        override fun applyReview(id: Long, review: EvaluationReview, reviewedAt: Instant): IncidentEvaluationDetail? = null
    }

    private fun row(id: Long, variant: String, faithfulness: Double) = IncidentEvaluationDetail(
        IncidentEvaluationSummary(
            id = id,
            incidentId = "inc-$id",
            promptVersion = "v2",
            judgeModel = "gpt-5.6-terra",
            analysisPromptVersion = if (variant == "A") "v1" else "v2",
            experimentName = "analysis-prompt-v2",
            experimentVariant = variant,
            faithfulness = faithfulness,
            actionability = 1.0,
            severityAccuracy = 0.7,
            failureMode = "없음",
            lowQuality = faithfulness < 0.7,
            evidenceAvailable = true,
            reviewStatus = ReviewStatus.NOT_REQUIRED,
            evaluatedAt = Instant.parse("2026-09-16T02:00:00Z"),
            createdAt = Instant.parse("2026-09-16T02:00:01Z"),
            updatedAt = Instant.parse("2026-09-16T02:00:01Z"),
        ),
        """{"scores": {"faithfulness": {"score": $faithfulness, "reason": "r"}}}""",
    )

    private fun mvc(vararg rows: IncidentEvaluationDetail) = MockMvcBuilders.standaloneSetup(
        ExperimentEvaluationsController(IncidentEvaluationService(FakeRepository(rows.toList()), ApplicationEventPublisher { })),
    ).build()

    @Test
    fun `실험의 평가 행을 variant·점수 포함 snake_case 로 반환한다 - 리포트 스크립트의 표본 원천`() {
        mvc(row(1, "B", 1.0), row(2, "A", 0.4))
            .perform(get("/api/experiments/analysis-prompt-v2/evaluations"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].incident_id").value("inc-1"))
            .andExpect(jsonPath("$[0].experiment_name").value("analysis-prompt-v2"))
            .andExpect(jsonPath("$[0].experiment_variant").value("B"))
            .andExpect(jsonPath("$[0].analysis_prompt_version").value("v2"))
            .andExpect(jsonPath("$[0].scores.faithfulness.score").value(1.0))
            .andExpect(jsonPath("$[1].experiment_variant").value("A"))
            .andExpect(jsonPath("$[1].low_quality").value(true))
    }

    @Test
    fun `평가가 없는 실험은 빈 목록으로 200`() {
        mvc().perform(get("/api/experiments/unknown/evaluations"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }
}
