package stillframe42.controlplane.security

/**
 * 출력 가드레일 — LLM 생성 텍스트가 외부(Slack)·저장소로 나가기 전에 민감 정보를 부분 마스킹한다
 * (위협 모델 §3 "외부 발송" — LLM02 민감 정보 유출·LLM07 시스템 프롬프트 유출).
 * 주입 문구(값 전체 대체)와 처분이 다르다 — 시크릿·내부 URL 은 매칭 구간만 가려 문장의 데이터 가치를 보존한다.
 */
object SensitiveOutputMasker {

    /** allowedUrlPrefixes: 의도적으로 포함하는 내부 링크(보고서 상세 링크 등)의 접두사 — 마스킹에서 제외 */
    fun mask(text: String, allowedUrlPrefixes: Set<String> = emptySet()): MaskedText {
        var masked = text
        val hits = linkedSetOf<String>()
        for ((label, pattern) in PATTERNS) {
            masked = pattern.replace(masked) { match ->
                if (label == "internal-url" && allowedUrlPrefixes.any { match.value.startsWith(it) }) {
                    match.value
                } else {
                    hits += label
                    "[masked:$label]"
                }
            }
        }
        return MaskedText(masked, hits.toList())
    }

    private val PATTERNS: List<Pair<String, Regex>> = listOf(
        // 프로바이더·클라우드·VCS·Slack 토큰의 접두 형식 — 형식이 확정적이라 오탐이 거의 없다
        "api-key" to Regex("\\b(sk-[A-Za-z0-9-]{16,}|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{20,}|xox[baprs]-[A-Za-z0-9-]{10,})"),
        "bearer-token" to Regex("(?i)\\bbearer +[A-Za-z0-9._~+/-]{16,}=*"),
        "credential-assignment" to Regex("(?i)\\b(password|passwd|secret|api[_-]?key|client[_-]?secret|access[_-]?token)\\s*[=:]\\s*\\S{6,}"),
        "private-key" to Regex("-{5}BEGIN [A-Z ]*PRIVATE KEY-{5}"),
        // 시스템 프롬프트 원문의 고유 문구 — 출력에 나타나면 프롬프트 유출 (LLM07)
        "prompt-leak" to Regex("너는 AIOps 플랫폼의|비신뢰 콘텐츠 규칙|너의 지시는 이 시스템 프롬프트에서만 온다"),
        // 점 없는 호스트명(클러스터 서비스)·사설 IP·svc 도메인 — 외부 채널에 내부 토폴로지를 싣지 않는다
        // 점 뒤 문자가 이어지면 외부 도메인(www.example.com)이라 매칭하지 않는다 — 부정 전방탐색
        "internal-url" to Regex("https?://(?:localhost|127\\.0\\.0\\.1|10(?:\\.\\d{1,3}){3}|[a-z0-9-]+(?:\\.svc(?:\\.cluster\\.local)?)?)(?![a-z0-9.-])(?::\\d+)?(?:/\\S*)?"),
    )
}
