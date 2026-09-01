package stillframe42.controlplane.security

/**
 * 저장 경로의 주입 문구 탐지 (위협 모델 벡터 ③ — RAG 자기 오염) — 보고서·시드가 pgvector/DB 에
 * 남으면 이후 모든 분석의 근거로 재주입되므로, 쓰기 시점에 확정 패턴을 스캔한다.
 * 패턴은 llm-gateway PatternInputGuardrail 의 확정(STRONG) 패턴과 같은 목록 (이식 — 변경 시 양쪽 동기화).
 * 약한 어휘 휴리스틱·LLM 2차 분류기는 두지 않는다 — 저장 경로는 오탐이 데이터 소실로 이어져 확정 패턴만 쓴다.
 */
object PromptInjectionScanner {

    /** 확정 패턴 매칭 시 패턴 식별 문자열(앞 40자), 아니면 null — 주입 문구 자체는 반환하지 않는다 (감사 로그 관례) */
    fun detect(text: String): String? {
        val candidates = listOf(ScanNormalizer.normalize(text)) +
            ScanNormalizer.decodedBase64Fragments(text).map { ScanNormalizer.normalize(it) }
        for (candidate in candidates) {
            STRONG_PATTERNS.firstOrNull { it.containsMatchIn(candidate) }?.let { return it.pattern.take(40) }
        }
        return null
    }

    private val STRONG_PATTERNS = listOf(
        Regex("(ignore|disregard|forget|override) (all |any |the |your )*(previous|prior|above|earlier|preceding|system) (instructions?|prompts?|rules?|directions?|guidelines?)"),
        Regex("(이전|앞의|앞선|위의|기존|지금까지의|모든) (지시|명령|프롬프트|규칙|지침)(을|를|은|는|들을|들은)? ?(전부|모두|다)? ?(무시|잊|버리|취소)"),
        Regex("(system prompt|시스템 프롬프트)(을|를|의)? ?[^ ]{0,10} ?[^ ]{0,10} ?(출력|공개|보여|인용|노출|print|reveal|show|output|repeat|display|dump)"),
        Regex("(reveal|print|show|output|repeat|dump) (me )?(your |the )?(full |entire |whole )?(system prompt|instructions|hidden prompt)"),
        Regex("(너|당신|you)(는|은|are)? ?(이제|now|지금부터) ?(a |an |the )?(?:[^ ]{0,12} ?){0,3}(관리자|admin|root|developer|운영자|jailbroken|dan)"),
        Regex("\\bsystem\\b ?(note|override|message)? ?[^ ]{0,3} ?(분석|절차|생략|stop|skip|ignore|중단)"),
        Regex("(developer|god|dan) mode"),
        Regex("new (system )?instructions? ?(follow|are|start)"),
    )
}
