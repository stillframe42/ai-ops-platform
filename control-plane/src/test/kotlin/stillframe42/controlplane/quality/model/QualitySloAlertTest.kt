package stillframe42.controlplane.quality.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import tools.jackson.databind.json.JsonMapper

/** Alertmanager 알림 → QualitySloAlert — 실험 라벨(aiops_experiment_*)은 variant 룰에만 있고 없으면 null */
class QualitySloAlertTest {

    private val mapper = JsonMapper.builder().build()

    @Test
    fun `variant 룰의 실험 라벨을 읽는다`() {
        val alert = QualitySloAlert.from(
            mapper.readTree(
                """
                {"status": "firing", "startsAt": "2026-09-16T03:00:00Z",
                 "labels": {"alertname": "AiopsVariantFaithfulnessLow", "severity": "warning", "kind": "quality", "cluster": "compose",
                            "aiops_experiment_name": "analysis-prompt-v2", "aiops_experiment_variant": "B"},
                 "annotations": {"summary": "실험 analysis-prompt-v2 variant B Faithfulness 24h 평균이 control 보다 0.12 낮다"}}
                """.trimIndent(),
            ),
        )!!

        assertEquals("analysis-prompt-v2", alert.experimentName)
        assertEquals("B", alert.experimentVariant)
    }

    @Test
    fun `모집단 룰에는 실험 라벨이 없다`() {
        val alert = QualitySloAlert.from(
            mapper.readTree(
                """{"status": "firing", "labels": {"alertname": "AiopsFaithfulnessLow", "kind": "quality"}, "annotations": {}}""",
            ),
        )!!

        assertNull(alert.experimentName)
        assertNull(alert.experimentVariant)
    }
}
