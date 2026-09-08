package stillframe42.llmgateway.security

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

class GatewayAuditFilterTest {

    private val filter = GatewayAuditFilter()
    private val appender = ListAppender<ILoggingEvent>()
    private val auditLogger = LoggerFactory.getLogger(GatewayAuditFilter.AUDIT_LOGGER) as Logger

    @BeforeTest
    fun attach() {
        appender.start()
        auditLogger.addAppender(appender)
    }

    @AfterTest
    fun detach() {
        auditLogger.detachAppender(appender)
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `v1 요청은 client_id·scope·경로·상태·캐시 판정을 MDC 필드로 기록한다`() {
        val jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("agent-service").claim("scope", listOf("ops:read", "llm:invoke")).build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt, listOf(SimpleGrantedAuthority("SCOPE_llm:invoke")))
        val request = MockHttpServletRequest("POST", "/v1/chat/completions").apply { addHeader("X-Task-Type", "root-cause-analysis") }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain(object : HttpServlet() {
            override fun service(req: HttpServletRequest, res: HttpServletResponse) {
                res.setHeader("X-Gateway-Cache", "exact_hit")
                res.setHeader("X-Gateway-Guardrail", "flagged")
                res.status = 200
            }
        })

        filter.doFilter(request, response, chain)

        val event = appender.list.single()
        val mdc = event.mdcPropertyMap
        assertEquals("gateway_request", mdc["audit.type"])
        assertEquals("agent-service", mdc["client_id"])
        assertEquals("ops:read llm:invoke", mdc["scope"])
        assertEquals("/v1/chat/completions", mdc["http.path"])
        assertEquals("root-cause-analysis", mdc["task_type"])
        assertEquals("200", mdc["http.status"])
        assertEquals("exact_hit", mdc["cache"])
        assertEquals("flagged", mdc["guardrail"])
        // 로그 행이 끝나면 MDC 는 비어 있어야 한다 — 다음 요청·다른 로그로 누수 금지
        assertTrue(MDC.getCopyOfContextMap().isNullOrEmpty())
    }

    @Test
    fun `감사 필드는 현재 SERVER 스팬 속성으로도 부여된다 - 값 없는 헤더는 속성 없음`() {
        val jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("agent-service").claim("scope", listOf("llm:invoke")).build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt, listOf(SimpleGrantedAuthority("SCOPE_llm:invoke")))
        val exporter = InMemorySpanExporter.create()
        val tracer = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build().get("test")
        val request = MockHttpServletRequest("POST", "/v1/chat/completions").apply { addHeader("X-Task-Type", "monitoring-summary") }
        val chain = MockFilterChain(object : HttpServlet() {
            override fun service(req: HttpServletRequest, res: HttpServletResponse) {
                res.setHeader("X-Gateway-Cache", "miss")
                res.setHeader("X-Gateway-Guardrail", "clean")
                res.status = 200
            }
        })

        val span = tracer.spanBuilder("http post /v1/chat/completions").startSpan()
        span.makeCurrent().use { filter.doFilter(request, MockHttpServletResponse(), chain) }
        span.end()

        val attributes = exporter.finishedSpanItems.single().attributes
        assertEquals("agent-service", attributes.get(AttributeKey.stringKey("aiops.client_id")))
        assertEquals("llm:invoke", attributes.get(AttributeKey.stringKey("aiops.scope")))
        assertEquals("monitoring-summary", attributes.get(AttributeKey.stringKey("gateway.task_type")))
        assertEquals("miss", attributes.get(AttributeKey.stringKey("gateway.cache")))
        assertEquals("clean", attributes.get(AttributeKey.stringKey("gateway.guardrail")))
        // 응답에 없는 판정(다운그레이드·폴백·가드레일 단계)은 속성도 없다 — 로그의 "none" 과 달리 스팬은 부재가 자연
        assertNull(attributes.get(AttributeKey.stringKey("gateway.downgrade")))
        assertNull(attributes.get(AttributeKey.stringKey("gateway.fallback")))
        assertNull(attributes.get(AttributeKey.stringKey("gateway.guardrail_stage")))
    }

    @Test
    fun `v1 밖 경로는 기록하지 않는다`() {
        filter.doFilter(MockHttpServletRequest("GET", "/actuator/health/readiness"), MockHttpServletResponse(), MockFilterChain())

        assertTrue(appender.list.isEmpty())
    }
}
