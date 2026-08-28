package stillframe42.llmgateway.ratelimit

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import stillframe42.llmgateway.relay.GatewayMetrics
import tools.jackson.databind.json.JsonMapper

class RateLimitInterceptorTest {

    private val registry = SimpleMeterRegistry()
    private val limiter = FakeRateLimiter()
    private val interceptor = RateLimitInterceptor(limiter, GatewayMetrics(registry), JsonMapper.builder().build())

    @AfterTest
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `한도 내 요청은 통과시킨다`() {
        limiter.next = RateLimitDecision(allowed = true)
        val response = MockHttpServletResponse()

        assertTrue(interceptor.preHandle(request("agent-service"), response, Any()))
        assertEquals(200, response.status)
    }

    @Test
    fun `한도 초과는 429와 Retry-After 로 거절한다`() {
        limiter.next = RateLimitDecision(allowed = false, retryAfterSeconds = 17)
        val response = MockHttpServletResponse()

        assertFalse(interceptor.preHandle(request("agent-service"), response, Any()))
        assertEquals(429, response.status)
        assertEquals("17", response.getHeader("Retry-After"))
        assertTrue(response.contentAsString.contains("rate_limit"))
        // Tomcat writer 는 charset 미지정 시 ISO-8859-1 로 인코딩해 한글 메시지가 ? 로 깨진다 (8/19 실측 —
        // mock 은 기본 UTF-8 이라 본문 단언으로는 재현 불가, charset 명시가 계약)
        assertEquals("UTF-8", response.characterEncoding)
        assertTrue(response.contentType!!.contains("charset=UTF-8"))
        assertEquals(1.0, registry.get("gateway.ratelimit.rejected").tags("service", "agent-service").counter().count(), 1e-9)
    }

    @Test
    fun `인증 컨텍스트가 없으면 anonymous 버킷으로 계량한다`() {
        limiter.next = RateLimitDecision(allowed = true)

        interceptor.preHandle(request(service = null), MockHttpServletResponse(), Any())

        assertEquals("anonymous", limiter.lastService)
    }

    /** 서비스 차원은 헤더가 아니라 검증된 토큰의 sub — 헤더를 넣어도 무시된다 */
    private fun request(service: String?) = MockHttpServletRequest("POST", "/v1/chat/completions").apply {
        addHeader("X-Client-Service", "forged-service")
        service?.let { authenticateAs(it) }
    }

    private fun authenticateAs(clientId: String) {
        val jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(clientId).build()
        SecurityContextHolder.getContext().authentication =
            JwtAuthenticationToken(jwt, listOf(SimpleGrantedAuthority("SCOPE_llm:invoke")))
    }

    private class FakeRateLimiter : RateLimiter {
        var next = RateLimitDecision(allowed = true)
        var lastService: String? = null

        override fun tryConsume(service: String): RateLimitDecision {
            lastService = service
            return next
        }
    }
}
