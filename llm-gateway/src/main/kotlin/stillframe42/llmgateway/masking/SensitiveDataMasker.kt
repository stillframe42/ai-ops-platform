package stillframe42.llmgateway.masking

/**
 * 시크릿·PII 패턴 마스킹 — 형식이 확정적인 패턴만 쓴다 (오탐이 분석 데이터를 지우는 비용 > 미탐 비용,
 * 로그 본문의 URI·수치는 분석의 원료라 공격적 패턴은 금지). 치환 문자열은 결정적 — 같은 입력의
 * 캐시 키(정확·의미)가 마스킹 후에도 안정적으로 일치해야 한다.
 */
object SensitiveDataMasker {

    fun mask(text: String): MaskingResult {
        var masked = text
        val hits = linkedMapOf<String, Int>()
        for ((label, pattern) in PATTERNS) {
            var count = 0
            masked = pattern.replace(masked) {
                count++
                "[masked:$label]"
            }
            if (count > 0) {
                hits[label] = count
            }
        }
        return MaskingResult(masked, hits)
    }

    private val PATTERNS: List<Pair<String, Regex>> = listOf(
        "api-key" to Regex("\\b(sk-[A-Za-z0-9-]{16,}|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{20,}|xox[baprs]-[A-Za-z0-9-]{10,})"),
        "bearer-token" to Regex("(?i)\\bbearer +[A-Za-z0-9._~+/-]{16,}=*"),
        "credential-assignment" to Regex("(?i)\\b(password|passwd|secret|api[_-]?key|client[_-]?secret|access[_-]?token)\\s*[=:]\\s*\\S{6,}"),
        "private-key" to Regex("-{5}BEGIN [A-Z ]*PRIVATE KEY-{5}"),
        "email" to Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"),
    )
}
