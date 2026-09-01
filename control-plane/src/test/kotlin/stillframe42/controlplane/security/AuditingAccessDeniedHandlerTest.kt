package stillframe42.controlplane.security

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import stillframe42.controlplane.audit.AuditLog

/** McpAuditFilterTest 와 같은 패턴 — audit 로거에 ListAppender 를 붙여 MDC 필드를 단언한다. */
class AuditingAccessDeniedHandlerTest {

    private val handler = AuditingAccessDeniedHandler()
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
    fun `스코프 밖 호출은 authz_denied 감사 행과 403 을 남긴다 - RT-16 경보 축`() {
        val jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("agent-service")
            .claim("scope", listOf("ops:read")).build()
        SecurityContextHolder.getContext().authentication =
            JwtAuthenticationToken(jwt, listOf(SimpleGrantedAuthority("SCOPE_ops:read")))
        val request = MockHttpServletRequest("POST", "/api/incidents/inc-1/approve")
        val response = MockHttpServletResponse()

        handler.handle(request, response, AccessDeniedException("denied"))

        assertEquals(403, response.status)
        val event = appender.list.single()
        assertEquals("authz_denied", event.mdcPropertyMap["audit.type"])
        assertEquals("agent-service", event.mdcPropertyMap["client_id"])
        assertEquals("POST", event.mdcPropertyMap["http.method"])
        assertEquals("/api/incidents/inc-1/approve", event.mdcPropertyMap["url.path"])
    }

    @Test
    fun `인증 정보가 없으면 anonymous 로 기록한다`() {
        val response = MockHttpServletResponse()

        handler.handle(MockHttpServletRequest("GET", "/api/incidents"), response, AccessDeniedException("denied"))

        assertEquals("anonymous", appender.list.single().mdcPropertyMap["client_id"])
    }
}
