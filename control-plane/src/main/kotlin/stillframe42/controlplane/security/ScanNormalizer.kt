package stillframe42.controlplane.security

import java.text.Normalizer
import java.util.Base64

/**
 * 패턴 매칭 전 정규화 — 인코딩 우회(전각·제로폭·자모 분리 표기·base64)를 평문으로 되돌린다.
 * llm-gateway InputNormalizer 와 같은 패턴 (모듈 간 코드 의존이 없어 이식 — 패턴 변경 시 양쪽 동기화).
 * 반환값은 매칭 전용 — 저장·발송되는 본문은 원문 기준으로 처리한다.
 */
object ScanNormalizer {

    // 제로폭·방향 제어 문자 — 표시되지 않으면서 토큰 경계를 끊는다
    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u2060\\uFEFF\\u00AD]")

    // 한 글자씩 띄어 쓴 영단어 (i g n o r e) — 3자 이상 연속일 때만 이어 붙인다 (약어 "a b" 오탐 방지)
    private val SPACED_LETTERS = Regex("\\b(?:[a-z] ){2,}[a-z]\\b")

    // base64 후보 — 16자 이상의 연속 base64 문자 (URL·해시와 겹치지만 디코드 결과가 텍스트일 때만 채택)
    private val BASE64_TOKEN = Regex("[A-Za-z0-9+/]{16,}={0,2}")

    fun normalize(text: String): String {
        var s = Normalizer.normalize(text, Normalizer.Form.NFKC)
        s = INVISIBLE.replace(s, "")
        s = s.lowercase()
        // 구분 기호를 공백으로 — URI 경로(ignore-previous-instructions)·snake_case 키가 단어 열이 된다
        s = s.replace(Regex("[-_/\\\\.,:;|*`'\"()\\[\\]{}<>]"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        s = SPACED_LETTERS.replace(s) { it.value.replace(" ", "") }
        return s
    }

    /** base64 로 감싼 지시문 — 디코드가 되고 대부분 인쇄 가능 문자면 평문으로 본다 */
    fun decodedBase64Fragments(text: String): List<String> =
        BASE64_TOKEN.findAll(text).mapNotNull { match ->
            runCatching { String(Base64.getDecoder().decode(match.value), Charsets.UTF_8) }
                .getOrNull()
                ?.takeIf { decoded -> decoded.isNotBlank() && decoded.count { it.isLetterOrDigit() || it.isWhitespace() } >= decoded.length * 0.9 }
        }.toList()
}
