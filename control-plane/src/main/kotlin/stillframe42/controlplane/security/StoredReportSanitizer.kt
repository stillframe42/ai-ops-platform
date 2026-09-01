package stillframe42.controlplane.security

import tools.jackson.databind.json.JsonMapper

/**
 * 보고서 저장 전 스캔 (위협 모델 벡터 ③) — 페이로드 JSON 의 모든 문자열 값을 걸어
 * 주입 확정 패턴은 값 전체를 대체하고(부분 삭제는 우회 여지가 남는다), 민감 정보는 부분 마스킹한다.
 * raw(jsonb 원문)와 추출 필드가 같은 원본에서 나오도록 파싱 전 페이로드 문자열 단계에서 수행한다.
 * 파싱 불가 페이로드는 원문 그대로 반환 — 판정은 IncidentReport.parse 의 몫이다.
 */
object StoredReportSanitizer {

    private val mapper = JsonMapper.builder().build()

    private const val INJECTION_REDACTED = "[저장 스캔 — 주입 의심 문구가 제거된 항목]"

    fun sanitize(payload: String): SanitizedReport {
        val root = runCatching { mapper.readValue(payload, Map::class.java) }.getOrNull()
            ?: return SanitizedReport(payload, emptyList())
        val findings = mutableListOf<String>()
        val sanitized = sanitizeValue(root, "$", findings)
        if (findings.isEmpty()) {
            return SanitizedReport(payload, emptyList())
        }
        return SanitizedReport(mapper.writeValueAsString(sanitized), findings)
    }

    private fun sanitizeValue(value: Any?, path: String, findings: MutableList<String>): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { (key, item) -> key to sanitizeValue(item, "$path.$key", findings) }
        is List<*> -> value.mapIndexed { index, item -> sanitizeValue(item, "$path[$index]", findings) }
        is String -> sanitizeString(value, path, findings)
        else -> value
    }

    private fun sanitizeString(value: String, path: String, findings: MutableList<String>): String {
        if (PromptInjectionScanner.detect(value) != null) {
            findings += "$path:injection"
            return INJECTION_REDACTED
        }
        val masked = SensitiveOutputMasker.mask(value)
        if (masked.hits.isNotEmpty()) {
            findings += masked.hits.map { "$path:$it" }
        }
        return masked.text
    }
}
