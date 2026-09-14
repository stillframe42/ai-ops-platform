package stillframe42.controlplane.evaluation.model

/**
 * 사람 검토 1건 — 목적지 상태 + 사람 라벨. 골든셋 라벨 규약(docs/quality-evaluation.md §2·§8)을 여기서 강제한다:
 * 점수는 앵커 4단계, 0.4 이하 차원이 있으면 실패 유형 필수, 전부 0.7 이상이면 `없음`. promoted 는 골든셋 행이 되므로
 * 점수·유형이 모두 있어야 하고, dismissed(Judge 오판·배울 것 없음)는 사유만으로 충분하다.
 */
data class EvaluationReview(
    val status: ReviewStatus,
    val humanScores: Map<String, Double>?,
    val failureMode: String?,
    val note: String?,
    val reviewedBy: String,
) {
    init {
        require(status.isReviewOutcome) { "검토 결과 상태는 reviewed·promoted·dismissed 중 하나: ${status.wire}" }
        if (status == ReviewStatus.PROMOTED) {
            requireNotNull(humanScores) { "promoted 는 human_scores 가 필요하다 (골든셋 행이 된다)" }
            requireNotNull(failureMode) { "promoted 는 failure_mode 가 필요하다" }
        }
        humanScores?.let { scores ->
            require(scores.keys == DIMENSIONS) { "human_scores 는 세 차원 전부: $DIMENSIONS" }
            scores.forEach { (dimension, score) ->
                require(score in ANCHORS) { "$dimension=$score 는 앵커 $ANCHORS 밖" }
            }
        }
        failureMode?.let { mode ->
            require(mode in FAILURE_MODES) { "failure_mode=$mode 는 $FAILURE_MODES 밖" }
            humanScores?.let { scores ->
                val lowest = scores.values.min()
                require(!(lowest <= 0.4 && mode == "없음")) { "0.4 이하 차원이 있으면 failure_mode 가 필요하다" }
                require(!(lowest >= 0.7 && mode != "없음")) { "전부 0.7 이상이면 failure_mode 는 없음" }
            }
        }
    }

    companion object {
        val DIMENSIONS = setOf("faithfulness", "actionability", "severity_accuracy")
        val ANCHORS = setOf(1.0, 0.7, 0.4, 0.0)
        val FAILURE_MODES = setOf("A", "B", "C", "D", "없음")
    }
}
