package stillframe42.controlplane.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.web.access.AccessDeniedHandler
import stillframe42.controlplane.audit.AuditLog

/**
 * 인가 실패(403) 경보 — 유효 토큰이 권한 밖 경로를 호출한 사건은 도구 오남용·권한 상승 시도의
 * 1차 신호라 감사 로그로 남긴다 (Loki `audit.type="authz_denied"` 가 경보 질의 축).
 * 기본 핸들러는 403 만 내고 흔적이 없다 — 응답은 동일하게 403 으로 유지한다.
 */
class AuditingAccessDeniedHandler : AccessDeniedHandler {

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        // McpAuditFilter 와 같은 패턴 — 인가 실패 시점에는 인증이 이미 끝나 SecurityContext 에 JWT 가 있다
        val jwt = (SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken)?.token
        val clientId = jwt?.subject ?: "anonymous"
        AuditLog.record(
            type = "authz_denied",
            fields = mapOf(
                "client_id" to clientId,
                "http.method" to request.method,
                "url.path" to request.requestURI,
            ),
            message = "인가 거부 — $clientId ${request.method} ${request.requestURI}",
        )
        response.sendError(HttpStatus.FORBIDDEN.value())
    }
}
