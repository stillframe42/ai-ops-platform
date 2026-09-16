package stillframe42.controlplane.quality.notify

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.web.client.RestClient
import stillframe42.controlplane.quality.model.QualitySloAlert
import stillframe42.controlplane.slack.SlackWebhookClient

/** 품질 SLO 메시지 포맷 — 발화는 리뷰 큐 링크 포함, 해소는 회복 표기만. HTTP 전송은 하지 않는다 */
class SlackQualitySloNotifierTest {

    private val notifier = SlackQualitySloNotifier(SlackWebhookClient("", RestClient.builder()), "http://localhost:8081")

    private fun alert(
        status: String,
        alertName: String = "AiopsFaithfulnessLow",
        experimentName: String? = null,
        experimentVariant: String? = null,
    ) = QualitySloAlert(
        alertName = alertName,
        status = status,
        severity = "warning",
        cluster = "compose",
        experimentName = experimentName,
        experimentVariant = experimentVariant,
        summary = "Faithfulness 1h 평균 0.62 < 0.85",
        description = "분석 프롬프트 변경·모델 교체 여부를 먼저 본다",
        startsAt = "2026-09-13T09:28:00Z",
    )

    @Test
    fun `발화 메시지는 요약·설명·시작 시각·리뷰 큐 링크를 담는다`() {
        val message = notifier.buildMessage(alert("firing"))

        assertTrue(message.contains("[품질 SLO] AiopsFaithfulnessLow* (warning · compose)"))
        assertTrue(message.contains("Faithfulness 1h 평균 0.62 < 0.85"))
        assertTrue(message.contains("시작: 2026-09-13T09:28:00Z"))
        assertTrue(message.contains("http://localhost:8081/api/evaluations/review-queue?status=pending_review"))
    }

    @Test
    fun `variant 룰이면 머리에 실험 좌표를 붙인다 - 어느 처리군이 나빠졌는지 메시지만으로 보이게`() {
        val message = notifier.buildMessage(
            alert("firing", alertName = "AiopsVariantFaithfulnessLow", experimentName = "analysis-prompt-v2", experimentVariant = "B"),
        )

        assertTrue(message.contains("[품질 SLO] AiopsVariantFaithfulnessLow* (warning · compose · 실험 analysis-prompt-v2 · B)"))
    }

    @Test
    fun `해소 메시지는 회복 표기이고 리뷰 큐 링크가 없다`() {
        val message = notifier.buildMessage(alert("resolved"))

        assertTrue(message.contains("[품질 SLO 해소] AiopsFaithfulnessLow"))
        assertFalse(message.contains("review-queue"))
    }
}
