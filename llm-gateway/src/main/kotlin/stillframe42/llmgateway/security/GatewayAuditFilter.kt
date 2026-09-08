package stillframe42.llmgateway.security

import io.opentelemetry.api.trace.Span
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import stillframe42.llmgateway.api.GatewayHeaders

/**
 * 게이트웨이 요청 감사 로그 (ADR-0016) — 누가(client_id·scope)·무엇을(경로·태스크)·결과(status·캐시 판정)를
 * 요청당 1행으로 남긴다. 필드는 MDC 로 싣는다 — docker 프로파일의 ECS JSON 이 MDC 를 최상위 필드로 내보내고
 * traceId 는 tracing 이 같은 경로로 채우므로 Loki 에서 `client_id`·`traceId` 축 질의가 된다.
 * 인증 뒤에 실행된다 — 빈 Filter 의 기본 순서가 security 필터 체인(-100) 뒤라 SecurityContext 가 채워져 있다.
 *
 * 같은 필드를 현재 SERVER 스팬(`http post /v1/...`)의 속성으로도 부여한다 (DAY 44, docs/otel-genai-mapping.md §5) —
 * Loki 축과 Tempo 축에서 같은 질의가 되도록. 식별은 플랫폼 확장 `aiops.*`, 판정은 agent-service 클라이언트 스팬과
 * 같은 키 `gateway.*` 라 양쪽 스팬을 한 키로 찾는다. 값이 없는 헤더는 속성도 두지 않는다 (로그의 "none" 과 다름).
 * 스팬이 없으면(tracing 미구성·단위 테스트) `Span.current()` 는 무효 스팬이라 부여가 조용히 무시된다.
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
        val clientId = ClientIdentity.current()
        val scopes = ClientIdentity.currentScopes()
        val taskType = request.getHeader(GatewayHeaders.TASK_TYPE)
        val fields = mapOf(
            "audit.type" to "gateway_request",
            "client_id" to clientId,
            "scope" to scopes,
            "http.path" to request.requestURI,
            "task_type" to (taskType ?: "none"),
            "http.status" to response.status.toString(),
            "cache" to (response.getHeader(GatewayHeaders.CACHE) ?: "none"),
            "guardrail" to (response.getHeader(GatewayHeaders.GUARDRAIL) ?: "none"),
        )
        withMdc(fields) {
            auditLogger.info(
                "gateway_request client_id={} path={} task={} status={} cache={} guardrail={}",
                fields["client_id"], fields["http.path"], fields["task_type"], fields["http.status"], fields["cache"], fields["guardrail"],
            )
        }
        promoteToSpan(clientId, scopes, taskType, response)
    }

    private fun promoteToSpan(clientId: String, scopes: String, taskType: String?, response: HttpServletResponse) {
        val span = Span.current()
        span.setAttribute(ATTR_CLIENT_ID, clientId)
        if (scopes.isNotEmpty()) span.setAttribute(ATTR_SCOPE, scopes)
        if (taskType != null) span.setAttribute(ATTR_TASK_TYPE, taskType)
        RESPONSE_HEADER_ATTRIBUTES.forEach { (header, attribute) ->
            response.getHeader(header)?.let { span.setAttribute(attribute, it) }
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

        // 서버 스팬 속성명 — agent-service `otel_genai.py` 의 `GATEWAY_RESPONSE_HEADERS` 와 같은 키 (클라이언트·서버 스팬 공용). 사용처가 이 필터뿐이라 여기 둔다
        private const val ATTR_CLIENT_ID = "aiops.client_id"
        private const val ATTR_SCOPE = "aiops.scope"
        private const val ATTR_TASK_TYPE = "gateway.task_type"
        private val RESPONSE_HEADER_ATTRIBUTES: Map<String, String> = mapOf(
            GatewayHeaders.CACHE to "gateway.cache",
            GatewayHeaders.GUARDRAIL to "gateway.guardrail",
            GatewayHeaders.GUARDRAIL_STAGE to "gateway.guardrail_stage",
            GatewayHeaders.DOWNGRADE to "gateway.downgrade",
            GatewayHeaders.FALLBACK to "gateway.fallback",
        )
    }
}
