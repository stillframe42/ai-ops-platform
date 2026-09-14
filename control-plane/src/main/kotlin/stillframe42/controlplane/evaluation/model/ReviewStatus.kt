package stillframe42.controlplane.evaluation.model

/**
 * 평가 1건의 사람 검토 상태. 저장 시 lowQuality 로 시작값이 갈리고, reviewed·promoted·dismissed 전이는 리뷰 API 몫.
 * DB 컬럼 값은 소문자 이름(wire) — 기존 status 컬럼(pending·approved…)과 같은 표기.
 * promoted 만 종결 상태다 — 골든셋에 이미 옮겨진 라벨을 API 로 바꾸면 파일과 DB 가 어긋난다. dismissed 는 재검토로 되돌릴 수 있다.
 */
enum class ReviewStatus(val wire: String) {
    NOT_REQUIRED("not_required"),
    PENDING_REVIEW("pending_review"),
    REVIEWED("reviewed"),
    PROMOTED("promoted"),
    DISMISSED("dismissed");

    /** 리뷰 API 가 목적지로 받을 수 있는 상태 — 시작값(not_required·pending_review)으로는 되돌리지 않는다 */
    val isReviewOutcome: Boolean
        get() = this == REVIEWED || this == PROMOTED || this == DISMISSED

    companion object {
        fun fromWire(value: String): ReviewStatus = entries.first { it.wire == value }

        fun fromWireOrNull(value: String): ReviewStatus? = entries.firstOrNull { it.wire == value }
    }
}
