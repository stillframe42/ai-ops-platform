package stillframe42.controlplane.evaluation.model

/**
 * 평가 1건의 사람 검토 상태. 저장 시 lowQuality 로 시작값이 갈리고, reviewed·promoted·dismissed 전이는 리뷰 API 몫.
 * DB 컬럼 값은 소문자 이름(wire) — 기존 status 컬럼(pending·approved…)과 같은 표기.
 */
enum class ReviewStatus(val wire: String) {
    NOT_REQUIRED("not_required"),
    PENDING_REVIEW("pending_review"),
    REVIEWED("reviewed"),
    PROMOTED("promoted"),
    DISMISSED("dismissed");

    companion object {
        fun fromWire(value: String): ReviewStatus = entries.first { it.wire == value }
    }
}
