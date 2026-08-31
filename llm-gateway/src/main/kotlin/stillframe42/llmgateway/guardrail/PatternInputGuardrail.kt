package stillframe42.llmgateway.guardrail

import org.springframework.stereotype.Component

/**
 * 1차 — 정규식(확정 패턴 → FLAGGED) + 어휘 휴리스틱(약한 신호 2개 이상 → SUSPECT, 2차 분류기 대상).
 * 매칭은 정규화 텍스트 + base64 디코드 조각에 대해 수행한다. 오탐 비용은 "플래깅 후 통과" 정책이 흡수한다.
 */
@Component
class PatternInputGuardrail {

    fun evaluate(text: String): GuardrailDecision {
        val candidates = listOf(InputNormalizer.normalize(text)) +
            InputNormalizer.decodedBase64Fragments(text).map { InputNormalizer.normalize(it) }
        for (candidate in candidates) {
            STRONG_PATTERNS.firstOrNull { it.containsMatchIn(candidate) }?.let {
                return GuardrailDecision(GuardrailVerdict.FLAGGED, GuardrailStage.PATTERN, reason = "pattern:${it.pattern.take(40)}")
            }
        }
        val hits = candidates.flatMap { candidate -> WEAK_LEXICON.filter { it.containsMatchIn(candidate) } }.toSet()
        if (hits.size >= WEAK_THRESHOLD) {
            return GuardrailDecision(GuardrailVerdict.SUSPECT, GuardrailStage.PATTERN, reason = "lexicon:${hits.size}")
        }
        return GuardrailDecision.CLEAN
    }

    companion object {
        private const val WEAK_THRESHOLD = 2

        // 교과서적 주입 문구 — 한/영. 정규화 후 텍스트(소문자·기호 제거·단일 공백)에 대해 매칭
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

        // 약한 신호 — 단독으로는 정상 운영 텍스트에도 흔하다 (approved·skip·instruction)
        private val WEAK_LEXICON = listOf(
            Regex("\\b(ignore|disregard|override|bypass)\\b"), Regex("무시|우회|생략"),
            Regex("\\binstructions?\\b"), Regex("지시|명령|지침"),
            Regex("\\bprompt\\b"), Regex("프롬프트"),
            Regex("\\b(admin|administrator|root)\\b"), Regex("관리자|운영자 권한"),
            Regex("\\brole\\b|\\bact as\\b"), Regex("역할"),
            Regex("(reply|answer|respond|output|say) (with |only )?(exactly|only)"), Regex("정확히|만 (써|답|출력)"),
            Regex("\\b(skip|stop|halt) (the )?(analysis|tools?|verification)\\b"), Regex("(분석|검증|도구)(을|를)? ?(중단|중지|금지|건너)"),
            Regex("\\b(already )?approved\\b"), Regex("승인 ?(됨|완료|받)"),
            Regex("\\bassistant\\b ?(instruction|must|should)"), Regex("must (output|answer|respond|stop)"),
            Regex("\\b(system note|system_note|hidden|secret) ?(instruction|prompt|note)?\\b"),
            Regex("\\bnotify only\\b|notify_only"),
        )
    }
}
