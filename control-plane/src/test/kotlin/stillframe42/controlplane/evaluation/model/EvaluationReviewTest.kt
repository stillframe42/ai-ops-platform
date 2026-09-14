package stillframe42.controlplane.evaluation.model

import kotlin.test.Test
import kotlin.test.assertFailsWith

/** 골든셋 라벨 규약(§2·§8)을 API 입력에도 같은 규칙으로 — build_golden.py 의 validate 와 거울 */
class EvaluationReviewTest {

    private val good = mapOf("faithfulness" to 0.7, "actionability" to 0.4, "severity_accuracy" to 1.0)

    @Test
    fun `승격은 앵커 점수 세 차원과 실패 유형이 있어야 한다`() {
        EvaluationReview(ReviewStatus.PROMOTED, good, "C", "사유", "sue")
        assertFailsWith<IllegalArgumentException> { EvaluationReview(ReviewStatus.PROMOTED, null, "C", null, "sue") }
        assertFailsWith<IllegalArgumentException> { EvaluationReview(ReviewStatus.PROMOTED, good, null, null, "sue") }
        assertFailsWith<IllegalArgumentException> { EvaluationReview(ReviewStatus.PROMOTED, good - "severity_accuracy", "C", null, "sue") }
        assertFailsWith<IllegalArgumentException> { EvaluationReview(ReviewStatus.PROMOTED, good + ("faithfulness" to 0.5), "C", null, "sue") }
    }

    @Test
    fun `실패 유형 규칙 - 0_4 이하면 유형 필수, 전부 0_7 이상이면 없음`() {
        assertFailsWith<IllegalArgumentException> { EvaluationReview(ReviewStatus.REVIEWED, good, "없음", null, "sue") }
        val allGood = good.mapValues { 1.0 }
        assertFailsWith<IllegalArgumentException> { EvaluationReview(ReviewStatus.REVIEWED, allGood, "A", null, "sue") }
        EvaluationReview(ReviewStatus.REVIEWED, allGood, "없음", null, "sue")
        assertFailsWith<IllegalArgumentException> { EvaluationReview(ReviewStatus.REVIEWED, good, "E", null, "sue") }
    }

    @Test
    fun `기각은 사유만으로 충분하고 시작 상태로는 되돌릴 수 없다`() {
        EvaluationReview(ReviewStatus.DISMISSED, null, null, "Judge 오판", "sue")
        assertFailsWith<IllegalArgumentException> { EvaluationReview(ReviewStatus.PENDING_REVIEW, null, null, null, "sue") }
        assertFailsWith<IllegalArgumentException> { EvaluationReview(ReviewStatus.NOT_REQUIRED, null, null, null, "sue") }
    }
}
