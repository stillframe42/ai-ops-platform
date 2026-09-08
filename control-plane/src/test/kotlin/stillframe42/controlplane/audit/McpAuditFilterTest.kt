package stillframe42.controlplane.audit

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

class McpAuditFilterTest {

    private val filter = McpAuditFilter()
    private val appender = ListAppender<ILoggingEvent>()
    private val auditLogger = LoggerFactory.getLogger(AuditLog.LOGGER_NAME) as Logger

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
    fun `tools call 요청은 client_id·scope·도구명을 MDC 필드로 남긴다 - 핸들러가 본문을 소비한 뒤에도`() {
        authenticate("agent-service", listOf("ops:read", "llm:invoke"))
        val request = MockHttpServletRequest("POST", "/mcp").apply {
            contentType = "application/json"
            setContent("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"getDeploymentHistory","arguments":{"app":"target-app"}}}""".toByteArray())
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain(BodyConsumingServlet()))

        val mdc = appender.list.single().mdcPropertyMap
        assertEquals(McpAuditFilter.TYPE, mdc["audit.type"])
        assertEquals("agent-service", mdc["client_id"])
        assertEquals("ops:read llm:invoke", mdc["scope"])
        assertEquals("tools/call", mdc["rpc.method"])
        assertEquals("getDeploymentHistory", mdc["tool"])
        assertEquals("200", mdc["http.status"])
        assertTrue(MDC.getCopyOfContextMap().isNullOrEmpty())
    }

    @Test
    fun `도구 호출이 아닌 JSON-RPC 는 tool 이 none 이다`() {
        authenticate("agent-service", listOf("ops:read"))
        val request = MockHttpServletRequest("POST", "/mcp").apply {
            setContent("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""".toByteArray())
        }

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain(BodyConsumingServlet()))

        val mdc = appender.list.single().mdcPropertyMap
        assertEquals("tools/list", mdc["rpc.method"])
        assertEquals("none", mdc["tool"])
    }

    @Test
    fun `인증 없이 거부된 요청도 anonymous 로 기록된다`() {
        val request = MockHttpServletRequest("POST", "/mcp").apply { setContent("{}".toByteArray()) }
        val response = MockHttpServletResponse().apply { status = 401 }

        filter.doFilter(request, response, MockFilterChain())

        val mdc = appender.list.single().mdcPropertyMap
        assertEquals(McpAuditFilter.ANONYMOUS, mdc["client_id"])
        assertEquals("401", mdc["http.status"])
    }

    @Test
    fun `캐시 상한을 넘는 본문도 핸들러는 전부 받고 감사는 unparsed 로 남긴다`() {
        authenticate("agent-service", listOf("ops:read"))
        // 상한(64KB) 초과 — 인자에 긴 문자열을 넣은 tools/call
        val body = """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"searchSimilarIncidents","arguments":{"symptom":"${"x".repeat(70 * 1024)}"}}}"""
        val request = MockHttpServletRequest("POST", "/mcp").apply { setContent(body.toByteArray()) }
        val servlet = CountingServlet()

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain(servlet))

        assertEquals(body.toByteArray().size, servlet.bytesRead)
        val mdc = appender.list.single().mdcPropertyMap
        assertEquals("unparsed", mdc["rpc.method"])
        assertEquals("none", mdc["tool"])
    }

    @Test
    fun `tools call 감사 필드는 현재 SERVER 스팬에 MCP 표준 키와 aiops 확장 키로 부여된다`() {
        authenticate("agent-service", listOf("ops:read"))
        val exporter = InMemorySpanExporter.create()
        val tracer = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build().get("test")
        val request = MockHttpServletRequest("POST", "/mcp").apply {
            remoteAddr = "10.244.1.7"
            setContent("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"getDeploymentHistory","arguments":{}}}""".toByteArray())
        }

        val span = tracer.spanBuilder("http post /mcp").startSpan()
        span.makeCurrent().use { filter.doFilter(request, MockHttpServletResponse(), MockFilterChain(BodyConsumingServlet())) }
        span.end()

        val attributes = exporter.finishedSpanItems.single().attributes
        assertEquals("agent-service", attributes.get(AttributeKey.stringKey("aiops.client_id")))
        assertEquals("ops:read", attributes.get(AttributeKey.stringKey("aiops.scope")))
        assertEquals("tools/call", attributes.get(AttributeKey.stringKey("mcp.method.name")))
        assertEquals("getDeploymentHistory", attributes.get(AttributeKey.stringKey("gen_ai.tool.name")))
        assertEquals("3", attributes.get(AttributeKey.stringKey("jsonrpc.request.id")))
        assertEquals("10.244.1.7", attributes.get(AttributeKey.stringKey("client.address")))
    }

    @Test
    fun `도구 호출이 아니거나 파싱 불가한 본문은 도구명·메서드 속성을 두지 않는다`() {
        val exporter = InMemorySpanExporter.create()
        val tracer = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build().get("test")
        val request = MockHttpServletRequest("POST", "/mcp").apply { setContent("not json".toByteArray()) }

        val span = tracer.spanBuilder("http post /mcp").startSpan()
        span.makeCurrent().use { filter.doFilter(request, MockHttpServletResponse().apply { status = 401 }, MockFilterChain()) }
        span.end()

        val attributes = exporter.finishedSpanItems.single().attributes
        assertEquals(McpAuditFilter.ANONYMOUS, attributes.get(AttributeKey.stringKey("aiops.client_id")))
        assertNull(attributes.get(AttributeKey.stringKey("aiops.scope")))
        assertNull(attributes.get(AttributeKey.stringKey("mcp.method.name")))
        assertNull(attributes.get(AttributeKey.stringKey("gen_ai.tool.name")))
        assertNull(attributes.get(AttributeKey.stringKey("jsonrpc.request.id")))
    }

    @Test
    fun `mcp 밖 경로는 기록하지 않는다`() {
        filter.doFilter(MockHttpServletRequest("GET", "/api/incidents"), MockHttpServletResponse(), MockFilterChain())

        assertTrue(appender.list.isEmpty())
    }

    private fun authenticate(clientId: String, scopes: List<String>) {
        val jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(clientId).claim("scope", scopes).build()
        SecurityContextHolder.getContext().authentication =
            JwtAuthenticationToken(jwt, scopes.map { SimpleGrantedAuthority("SCOPE_$it") })
    }

    private class CountingServlet : HttpServlet() {
        var bytesRead = 0
        override fun service(req: HttpServletRequest, res: HttpServletResponse) {
            bytesRead = req.inputStream.readAllBytes().size
            res.status = 200
        }
    }

    /** MCP 핸들러처럼 본문을 끝까지 읽는다 — 캐시 래퍼의 사후 재독 검증용 */
    private class BodyConsumingServlet : HttpServlet() {
        override fun service(req: HttpServletRequest, res: HttpServletResponse) {
            req.inputStream.readAllBytes()
            res.status = 200
        }
    }
}
