package stillframe42.controlplane.evaluation.notify

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.web.client.RestClient
import stillframe42.controlplane.evaluation.model.IncidentEvaluation
import stillframe42.controlplane.slack.SlackWebhookClient

/** 검토 요청 메시지 포맷 — 인시던트·세 점수·유형·0.7 미만 차원의 Judge 사유만·검토 API 경로. HTTP 전송은 하지 않는다 */
class SlackReviewRequestNotifierTest {

    private val notifier = SlackReviewRequestNotifier(SlackWebhookClient("", RestClient.builder()), "http://localhost:8081")

    private val evaluation = IncidentEvaluation.parse(
        """
        {"incident_id": "inc-error-rate-surge-20260911110732-b68558",
         "scores": {"faithfulness": {"score": 0.7, "reason": "회복 시점 언급 없음"}, "actionability": {"score": 0.4, "reason": "RESTART 배제·CIRCUIT_BREAK 만"},
                    "severity_accuracy": {"score": 1.0, "reason": "P2 타당"}},
         "failure_mode": "B", "low_quality": true, "judge_model": "gpt-5.6-terra", "prompt_version": "v2",
         "analysis_prompt_version": "v1", "evidence_available": true, "evaluated_at": "2026-09-11T11:08:31+00:00"}
        """.trimIndent(),
    )!!

    @Test
    fun `저품질 차원의 Judge 사유만 싣고 검토 API 경로를 안내한다`() {
        val message = notifier.buildMessage(41, evaluation)

        assertTrue(message.contains("[품질] 저품질 보고서"))
        assertTrue(message.contains("실패 유형 B"))
        assertTrue(message.contains("inc-error-rate-surge-20260911110732-b68558"))
        assertTrue(message.contains("F 0.7 · A 0.4 · S 1.0"))
        assertTrue(message.contains("분석 프롬프트 v1"))
        assertTrue(message.contains("Actionability Judge 사유: RESTART 배제·CIRCUIT_BREAK 만"))
        assertFalse(message.contains("P2 타당"))
        assertTrue(message.contains("POST http://localhost:8081/api/evaluations/41/review"))
        assertTrue(message.contains("http://localhost:8081/api/incidents/inc-error-rate-surge-20260911110732-b68558"))
        assertFalse(message.contains("실험:"))
    }

    @Test
    fun `실험 배정이 있으면 실험 좌표 한 줄을 싣는다 - 저품질이 한 variant 에 몰리는지 보이게`() {
        val assigned = IncidentEvaluation.parse(
            """
            {"incident_id": "inc-latency-surge-20260915054138-2a8a92",
             "scores": {"faithfulness": {"score": 0.6, "reason": "근거 없음"}, "actionability": {"score": 0.7, "reason": "-"},
                        "severity_accuracy": {"score": 1.0, "reason": "-"}},
             "failure_mode": "A", "low_quality": true, "judge_model": "gpt-5.6-terra", "prompt_version": "v2",
             "analysis_prompt_version": "v2", "experiment_name": "analysis-prompt-v2", "experiment_variant": "B",
             "evidence_available": true, "evaluated_at": "2026-09-15T06:10:00+00:00"}
            """.trimIndent(),
        )!!

        val message = notifier.buildMessage(7, assigned)

        assertTrue(message.contains("• 실험: analysis-prompt-v2 · variant B"))
    }

    @Test
    fun `URL 미설정이면 전송 시도 없이 조용히 반환한다`() {
        notifier.requestReview(41, evaluation)
        assertTrue(true)
    }
}
