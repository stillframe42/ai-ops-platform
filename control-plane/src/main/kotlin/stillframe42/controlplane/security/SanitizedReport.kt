package stillframe42.controlplane.security

/** 저장 스캔 결과 — findings 는 "JSON 경로:패턴 라벨" 목록, 비어 있으면 payload 는 원문 그대로다 */
data class SanitizedReport(
    val payload: String,
    val findings: List<String>,
)
