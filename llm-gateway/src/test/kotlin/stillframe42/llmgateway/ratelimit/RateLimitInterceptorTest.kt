package stillframe42.llmgateway.ratelimit

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import stillframe42.llmgateway.relay.GatewayMetrics
import tools.jackson.databind.json.JsonMapper

class RateLimitInterceptorTest {

    private val registry = SimpleMeterRegistry()
    private val limiter = FakeRateLimiter()
    private val interceptor = RateLimitInterceptor(limiter, GatewayMetrics(registry), JsonMapper.builder().build())

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
    fun `서비스 헤더 부재는 unknown 버킷으로 계량한다`() {
        limiter.next = RateLimitDecision(allowed = true)

        interceptor.preHandle(request(service = null), MockHttpServletResponse(), Any())

        assertEquals("unknown", limiter.lastService)
    }

    private fun request(service: String?) = MockHttpServletRequest("POST", "/v1/chat/completions").apply {
        service?.let { addHeader("X-Client-Service", it) }
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
