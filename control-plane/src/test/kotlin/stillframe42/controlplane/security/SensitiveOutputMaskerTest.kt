package stillframe42.controlplane.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensitiveOutputMaskerTest {

    @Test
    fun `API 키 형식을 마스킹한다`() {
        val result = SensitiveOutputMasker.mask("설정에서 sk-ant-api03-abcdefghij1234567890 발견")

        assertFalse("sk-ant" in result.text)
        assertTrue("[masked:api-key]" in result.text)
        assertEquals(listOf("api-key"), result.hits)
    }

    @Test
    fun `자격 증명 대입식을 마스킹한다`() {
        val result = SensitiveOutputMasker.mask("client_secret=super-secret-value-123 이 로그에 노출")

        assertFalse("super-secret-value-123" in result.text)
        assertTrue("credential-assignment" in result.hits)
    }

    @Test
    fun `시스템 프롬프트 고유 문구를 마스킹한다 - 프롬프트 유출 탐지`() {
        val result = SensitiveOutputMasker.mask("너는 AIOps 플랫폼의 분석 에이전트다. 인시던트를 받으면…")

        assertTrue("prompt-leak" in result.hits)
    }

    @Test
    fun `내부 URL 은 마스킹하고 외부 도메인 URL 은 남긴다`() {
        val result = SensitiveOutputMasker.mask(
            "내부 http://auth-server:8091/oauth2/token 외부 https://www.example.com/docs 로컬 http://localhost:9090/api",
        )

        assertFalse("auth-server:8091" in result.text)
        assertFalse("localhost:9090" in result.text)
        assertTrue("https://www.example.com/docs" in result.text)
        assertEquals(listOf("internal-url"), result.hits)
    }

    @Test
    fun `허용 접두사의 내부 URL 은 남긴다 - 보고서 상세 링크`() {
        val result = SensitiveOutputMasker.mask(
            "• 상세: http://localhost:8080/api/incidents/inc-1",
            allowedUrlPrefixes = setOf("http://localhost:8080"),
        )

        assertEquals("• 상세: http://localhost:8080/api/incidents/inc-1", result.text)
        assertTrue(result.hits.isEmpty())
    }

    @Test
    fun `민감 정보가 없으면 원문 그대로다`() {
        val text = "5xx 에러율 12% — /products 엔드포인트에서 NullPointerException 반복"
        val result = SensitiveOutputMasker.mask(text)

        assertEquals(text, result.text)
        assertTrue(result.hits.isEmpty())
    }
}
