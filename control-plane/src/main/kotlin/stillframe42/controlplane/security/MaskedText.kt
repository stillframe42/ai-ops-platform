package stillframe42.controlplane.security

/** 마스킹 결과 — hits 는 매칭된 패턴 라벨(중복 제거), 마스킹된 본문 자체는 hits 에 싣지 않는다 */
data class MaskedText(
    val text: String,
    val hits: List<String>,
)
