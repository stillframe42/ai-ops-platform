package stillframe42.controlplane.evaluation.controller

import java.time.Instant
import kotlin.test.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import stillframe42.controlplane.evaluation.model.IncidentEvaluation
import stillframe42.controlplane.evaluation.model.IncidentEvaluationDetail
import stillframe42.controlplane.evaluation.model.IncidentEvaluationSummary
import stillframe42.controlplane.evaluation.model.ReviewStatus
import stillframe42.controlplane.evaluation.repository.IncidentEvaluationRepository
import stillframe42.controlplane.evaluation.service.IncidentEvaluationService

/** standalone MockMvc + fake 저장소 — API 와이어(snake_case)·빈 목록 규약만 검증 (IncidentQueryControllerTest 와 같은 조립) */
class IncidentEvaluationControllerTest {

    private val incidentId = "inc-latency-surge-20260910061900-abc123"

    private val summary = IncidentEvaluationSummary(
        id = 7,
        incidentId = incidentId,
        promptVersion = "v1",
        judgeModel = "gpt-5.6-terra",
        analysisPromptVersion = "v1",
        faithfulness = 0.4,
        actionability = 0.7,
        severityAccuracy = 1.0,
        failureMode = "B",
        lowQuality = true,
        evidenceAvailable = true,
        reviewStatus = ReviewStatus.PENDING_REVIEW,
        evaluatedAt = Instant.parse("2026-09-11T02:00:00Z"),
        createdAt = Instant.parse("2026-09-11T02:00:01Z"),
        updatedAt = Instant.parse("2026-09-11T02:00:01Z"),
    )

    private val raw = """{"scores": {"faithfulness": {"score": 0.4, "reason": "이전 회차 OOM"}}, "failure_mode": "B"}"""

    private class FakeRepository(private val details: List<IncidentEvaluationDetail>) : IncidentEvaluationRepository {
        override fun upsert(evaluation: IncidentEvaluation): Boolean = true
        override fun findByIncidentId(incidentId: String): List<IncidentEvaluationDetail> =
            details.filter { it.summary.incidentId == incidentId }
    }

    private fun mvc(repository: IncidentEvaluationRepository) =
        MockMvcBuilders.standaloneSetup(IncidentEvaluationController(IncidentEvaluationService(repository))).build()

    @Test
    fun `평가 목록은 snake_case 필드와 차원별 score·reason 객체를 반환한다`() {
        val mvc = mvc(FakeRepository(listOf(IncidentEvaluationDetail(summary, raw))))

        mvc.perform(get("/api/incidents/$incidentId/evaluations"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].incident_id").value(incidentId))
            .andExpect(jsonPath("$[0].judge_model").value("gpt-5.6-terra"))
            .andExpect(jsonPath("$[0].review_status").value("pending_review"))
            .andExpect(jsonPath("$[0].low_quality").value(true))
            .andExpect(jsonPath("$[0].scores.faithfulness.score").value(0.4))
            .andExpect(jsonPath("$[0].scores.faithfulness.reason").value("이전 회차 OOM"))
    }

    @Test
    fun `평가가 없으면 빈 목록 - 404 가 아니다`() {
        mvc(FakeRepository(emptyList())).perform(get("/api/incidents/inc-unknown/evaluations"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }
}
