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

/** standalone MockMvc + fake 저장소 — 실험 요약 와이어(snake_case)·빈 variants 규약만 검증. 집계 규칙은 서비스 테스트 몫 */
class ExperimentSummaryControllerTest {

    private class FakeRepository(private val rows: List<IncidentEvaluationDetail>) : IncidentEvaluationRepository {
        override fun upsert(evaluation: IncidentEvaluation): UpsertResult = UpsertResult(1, true)
        override fun findByIncidentId(incidentId: String): List<IncidentEvaluationDetail> = emptyList()
        override fun findById(id: Long): IncidentEvaluationDetail? = null
        override fun findByReviewStatus(status: ReviewStatus, limit: Int): List<IncidentEvaluationDetail> = emptyList()
        override fun findByExperimentName(experimentName: String): List<IncidentEvaluationDetail> =
            rows.filter { it.summary.experimentName == experimentName }
        override fun applyReview(id: Long, review: EvaluationReview, reviewedAt: Instant): IncidentEvaluationDetail? = null
    }

    private fun row(id: Long, variant: String, faithfulness: Double, lowQuality: Boolean) = IncidentEvaluationDetail(
        IncidentEvaluationSummary(
            id = id,
            incidentId = "inc-$id",
            promptVersion = "v2",
            judgeModel = "gpt-5.6-terra",
            analysisPromptVersion = "v1",
            experimentName = "analysis-prompt-v2",
            experimentVariant = variant,
            faithfulness = faithfulness,
            actionability = 1.0,
            severityAccuracy = 0.7,
            failureMode = "없음",
            lowQuality = lowQuality,
            evidenceAvailable = true,
            reviewStatus = ReviewStatus.NOT_REQUIRED,
            evaluatedAt = Instant.parse("2026-09-14T02:00:00Z"),
            createdAt = Instant.parse("2026-09-14T02:00:01Z"),
            updatedAt = Instant.parse("2026-09-14T02:00:01Z"),
        ),
        "{}",
    )

    private fun mvc(vararg rows: IncidentEvaluationDetail) = MockMvcBuilders.standaloneSetup(
        ExperimentSummaryController(IncidentEvaluationService(FakeRepository(rows.toList()), ApplicationEventPublisher { })),
    ).build()

    @Test
    fun `실험 요약은 variant 별 snake_case 집계를 이름순으로 반환한다`() {
        mvc(
            row(1, "B", faithfulness = 1.0, lowQuality = false),
            row(2, "A", faithfulness = 0.4, lowQuality = true),
            row(3, "B", faithfulness = 0.7, lowQuality = false),
        ).perform(get("/api/experiments/analysis-prompt-v2/summary"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.experiment").value("analysis-prompt-v2"))
            .andExpect(jsonPath("$.variants.length()").value(2))
            .andExpect(jsonPath("$.variants[0].variant").value("A"))
            .andExpect(jsonPath("$.variants[0].n").value(1))
            .andExpect(jsonPath("$.variants[0].faithfulness_avg").value(0.4))
            .andExpect(jsonPath("$.variants[0].actionability_avg").value(1.0))
            .andExpect(jsonPath("$.variants[0].severity_accuracy_avg").value(0.7))
            .andExpect(jsonPath("$.variants[0].low_quality_rate").value(1.0))
            .andExpect(jsonPath("$.variants[1].variant").value("B"))
            .andExpect(jsonPath("$.variants[1].n").value(2))
            .andExpect(jsonPath("$.variants[1].faithfulness_avg").value(0.85))
            .andExpect(jsonPath("$.variants[1].low_quality_rate").value(0.0))
    }

    @Test
    fun `평가가 없는 실험은 빈 variants 로 200 - 404 가 아니다`() {
        mvc().perform(get("/api/experiments/unknown/summary"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.experiment").value("unknown"))
            .andExpect(jsonPath("$.variants.length()").value(0))
    }
}
