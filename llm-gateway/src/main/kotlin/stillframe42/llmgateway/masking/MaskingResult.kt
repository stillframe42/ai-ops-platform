package stillframe42.llmgateway.masking

/** 마스킹 결과 — hits 는 패턴 라벨별 치환 횟수, 원문(마스킹 전) 값은 싣지 않는다 (감사 로그 관례) */
data class MaskingResult(
    val text: String,
    val hits: Map<String, Int>,
)
