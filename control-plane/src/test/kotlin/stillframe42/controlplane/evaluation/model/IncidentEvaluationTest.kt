package stillframe42.controlplane.evaluation.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IncidentEvaluationTest {

    private val payload = """
        {"incident_id": "inc-latency-surge-20260910061900-abc123",
         "scores": {"faithfulness": {"score": 0.4, "reason": "이전 회차 OOM 을 현재 원인으로"},
                    "actionability": {"score": 0.7, "reason": "RESTART 타당"},
                    "severity_accuracy": {"score": 1.0, "reason": "P2 타당"}},
         "failure_mode": "B", "low_quality": true, "judge_model": "gpt-5.6-terra", "prompt_version": "v1",
         "analysis_prompt_version": "v1", "experiment_name": "analysis-prompt-v2", "experiment_variant": "B",
         "evidence_available": true, "evaluated_at": "2026-09-11T02:00:00.123456+00:00"}
    """.trimIndent()

    @Test
    fun `발행 페이로드를 차원 점수·판정·원문으로 읽는다`() {
        val evaluation = IncidentEvaluation.parse(payload)!!

        assertEquals("inc-latency-surge-20260910061900-abc123", evaluation.incidentId)
        assertEquals(0.4, evaluation.faithfulness)
        assertEquals(0.7, evaluation.actionability)
        assertEquals(1.0, evaluation.severityAccuracy)
        assertEquals("B", evaluation.failureMode)
        assertEquals(true, evaluation.lowQuality)
        assertEquals("v1", evaluation.analysisPromptVersion)
        assertEquals("analysis-prompt-v2", evaluation.experimentName)
        assertEquals("B", evaluation.experimentVariant)
        assertEquals(Instant.parse("2026-09-11T02:00:00.123456Z"), evaluation.evaluatedAt)
        assertEquals(ReviewStatus.PENDING_REVIEW, evaluation.initialReviewStatus())
        assertEquals(payload, evaluation.raw)
    }

    @Test
    fun `저품질이 아니면 검토 불요 상태로 시작한다`() {
        val fine = payload.replace("\"low_quality\": true", "\"low_quality\": false")
        assertEquals(ReviewStatus.NOT_REQUIRED, IncidentEvaluation.parse(fine)!!.initialReviewStatus())
    }

    @Test
    fun `실험 필드는 없거나 null 이면 null - 실험 밖 평가도 그대로 저장한다`() {
        val absent = payload.replace("\"experiment_name\": \"analysis-prompt-v2\", \"experiment_variant\": \"B\",", "")
        assertNull(IncidentEvaluation.parse(absent)!!.experimentName)
        assertNull(IncidentEvaluation.parse(absent)!!.experimentVariant)

        val explicitNull = payload.replace("\"experiment_variant\": \"B\"", "\"experiment_variant\": null")
        assertEquals("analysis-prompt-v2", IncidentEvaluation.parse(explicitNull)!!.experimentName)
        assertNull(IncidentEvaluation.parse(explicitNull)!!.experimentVariant)
    }

    @Test
    fun `차원 점수·식별 필드가 빠지면 null - poison pill 은 건너뛴다`() {
        assertNull(IncidentEvaluation.parse("not-json{{{"))
        assertNull(IncidentEvaluation.parse(payload.replace("\"actionability\": {\"score\": 0.7, \"reason\": \"RESTART 타당\"},", "")))
        assertNull(IncidentEvaluation.parse(payload.replace("\"judge_model\": \"gpt-5.6-terra\",", "")))
        assertNull(IncidentEvaluation.parse(payload.replace("\"evaluated_at\": \"2026-09-11T02:00:00.123456+00:00\"", "\"evaluated_at\": \"어제\"")))
    }
}
