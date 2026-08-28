package stillframe42.llmgateway.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 게이트웨이 요청 감사 로그 (ADR-0016) — 누가(client_id·scope)·무엇을(경로·태스크)·결과(status·캐시 판정)를
 * 요청당 1행으로 남긴다. 필드는 MDC 로 싣는다 — docker 프로파일의 ECS JSON 이 MDC 를 최상위 필드로 내보내고
 * traceId 는 tracing 이 같은 경로로 채우므로 Loki 에서 `client_id`·`traceId` 축 질의가 된다.
 * 인증 뒤에 실행된다 — 빈 Filter 의 기본 순서가 security 필터 체인(-100) 뒤라 SecurityContext 가 채워져 있다.
 */
@Component
class GatewayAuditFilter : OncePerRequestFilter() {

    private val auditLogger = LoggerFactory.getLogger(AUDIT_LOGGER)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith("/v1/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        try {
            filterChain.doFilter(request, response)
        } finally {
            record(request, response)
        }
    }

    private fun record(request: HttpServletRequest, response: HttpServletResponse) {
        val fields = mapOf(
            "audit.type" to "gateway_request",
            "client_id" to ClientIdentity.current(),
            "scope" to ClientIdentity.currentScopes(),
            "http.path" to request.requestURI,
            "task_type" to (request.getHeader("X-Task-Type") ?: "none"),
            "http.status" to response.status.toString(),
            "cache" to (response.getHeader("X-Gateway-Cache") ?: "none"),
        )
        withMdc(fields) {
            auditLogger.info(
                "gateway_request client_id={} path={} task={} status={} cache={}",
                fields["client_id"], fields["http.path"], fields["task_type"], fields["http.status"], fields["cache"],
            )
        }
    }

    private fun withMdc(fields: Map<String, String>, block: () -> Unit) {
        val closeables = fields.map { (k, v) -> MDC.putCloseable(k, v) }
        try {
            block()
        } finally {
            closeables.forEach { it.close() }
        }
    }

    companion object {
        /** 감사 전용 로거명 — Loki 에서 `logger.name="audit"` 로 감사 행만 분리 조회 */
        const val AUDIT_LOGGER = "audit"
    }
}
