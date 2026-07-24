package stillframe42.controlplane.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/** 단위 테스트 경계 — 서블릿 컨테이너 무의존, Mock 요청/응답으로 필터 규약만 검증. */
class McpApiKeyFilterTest {

    private fun request(header: String? = null) =
        MockHttpServletRequest("POST", "/mcp").apply {
            if (header != null) addHeader(McpApiKeyFilter.API_KEY_HEADER, header)
        }

    @Test
    fun `올바른 키 헤더면 체인을 통과시킨다`() {
        val filter = McpApiKeyFilter("secret-key")
        val chain = MockFilterChain()
        val response = MockHttpServletResponse()

        filter.doFilter(request(header = "secret-key"), response, chain)

        // 체인이 실행됐다면 MockFilterChain 에 요청이 전달돼 있다
        assertEquals("/mcp", (chain.request as MockHttpServletRequest).requestURI)
        assertEquals(200, response.status)
    }

    @Test
    fun `헤더가 없으면 401 과 JSON 오류 본문을 반환한다`() {
        val filter = McpApiKeyFilter("secret-key")
        val chain = MockFilterChain()
        val response = MockHttpServletResponse()

        filter.doFilter(request(header = null), response, chain)

        assertNull(chain.request) // 체인 미실행 — 도구 핸들러까지 도달하지 않는다
        assertEquals(401, response.status)
        assertTrue(response.contentType!!.startsWith("application/json"))
        assertTrue(response.contentAsString.contains("unauthorized"))
    }

    @Test
    fun `키가 일치하지 않으면 401 을 반환한다`() {
        val filter = McpApiKeyFilter("secret-key")
        val chain = MockFilterChain()
        val response = MockHttpServletResponse()

        filter.doFilter(request(header = "wrong-key"), response, chain)

        assertNull(chain.request)
        assertEquals(401, response.status)
    }

    @Test
    fun `키 미설정이면 필터 등록이 비활성이다 - 로컬 개발 인증 생략`() {
        val registration = McpSecurityConfig().mcpApiKeyFilterRegistration("")

        assertFalse(registration.isEnabled)
    }

    @Test
    fun `키 설정 시 mcp 경로에만 필터가 등록된다`() {
        val registration = McpSecurityConfig().mcpApiKeyFilterRegistration("secret-key")

        assertTrue(registration.isEnabled)
        assertEquals(setOf("/mcp", "/mcp/*"), registration.urlPatterns.toSet())
    }
}
