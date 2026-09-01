package stillframe42.llmgateway.masking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensitiveDataMaskerTest {

    @Test
    fun `API 키 형식을 라벨별로 마스킹하고 치환 횟수를 센다`() {
        val result = SensitiveDataMasker.mask(
            "키 sk-ant-api03-abcdefghij1234567890 와 AKIAABCDEFGHIJKLMNOP 발견",
        )

        assertFalse("sk-ant" in result.text)
        assertFalse("AKIA" in result.text)
        assertEquals(2, result.hits["api-key"])
    }

    @Test
    fun `자격 증명 대입식과 이메일을 마스킹한다`() {
        val result = SensitiveDataMasker.mask("접속자 ops@example.com, client_secret=abc123def456")

        assertFalse("ops@example.com" in result.text)
        assertFalse("abc123def456" in result.text)
        assertEquals(setOf("email", "credential-assignment"), result.hits.keys)
    }

    @Test
    fun `치환 문자열은 결정적이다 - 같은 입력이면 캐시 키가 일치해야 한다`() {
        val text = "Authorization: Bearer abcdefghijklmnopqrstuvwx 로 호출"

        assertEquals(SensitiveDataMasker.mask(text).text, SensitiveDataMasker.mask(text).text)
        assertTrue("[masked:bearer-token]" in SensitiveDataMasker.mask(text).text)
    }

    @Test
    fun `로그 분석 원료는 건드리지 않는다 - URI·수치·내부 호스트명`() {
        val text = "GET /products/42 500 — http://target-app:8080 에서 NullPointerException, 에러율 12%"
        val result = SensitiveDataMasker.mask(text)

        assertEquals(text, result.text)
        assertTrue(result.hits.isEmpty())
    }
}
