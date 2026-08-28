package stillframe42.controlplane.audit

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingRequestWrapper
import tools.jackson.databind.json.JsonMapper

/**
 * MCP 요청 감사 (ADR-0016) — client_id·scope·JSON-RPC method·도구명·상태를 요청당 1행.
 * 서블릿 필터인 이유: 도구 본체는 MCP 서버의 별도 스레드에서 실행될 수 있어 SecurityContext 가 닿지 않는다 —
 * 인증 정보가 확실히 있는 서블릿 스레드에서 본문(JSON-RPC)만 읽어 기록한다. 인증 뒤에 실행된다 (빈 Filter 기본 순서 >
 * security 체인). 본문은 캐시 래퍼로 재독 — MCP 핸들러가 소비한 뒤 사후에 읽는다.
 */
@Component
class McpAuditFilter : OncePerRequestFilter() {

    private val mapper = JsonMapper.builder().build()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith(MCP_PATH)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        // 캐시 상한 — 도구 인자(PromQL·증상 문장)는 수 KB 라 감사에는 앞부분이면 충분하다
        val wrapped = ContentCachingRequestWrapper(request, BODY_CACHE_LIMIT)
        try {
            filterChain.doFilter(wrapped, response)
        } finally {
            record(wrapped, response)
        }
    }

    private fun record(request: ContentCachingRequestWrapper, response: HttpServletResponse) {
        val rpc = parseRpc(request.contentAsByteArray)
        val jwt = (SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken)?.token
        val fields = mapOf(
            "client_id" to (jwt?.subject ?: ANONYMOUS),
            "scope" to jwt?.getClaimAsStringList("scope")?.joinToString(" ").orEmpty(),
            "http.method" to request.method,
            "rpc.method" to rpc.method,
            "tool" to rpc.tool,
            "http.status" to response.status.toString(),
        )
        AuditLog.record(
            TYPE,
            fields,
            "client_id=${fields["client_id"]} ${request.method} rpc=${rpc.method} tool=${rpc.tool} status=${response.status}",
        )
    }

    private fun parseRpc(body: ByteArray): RpcCall {
        if (body.isEmpty()) return RpcCall(NONE, NONE)
        return try {
            val node = mapper.readTree(body)
            val method = node.path("method").asString().ifEmpty { NONE }
            val tool = node.path("params").path("name").asString().ifEmpty { NONE }
            RpcCall(method, tool)
        } catch (_: Exception) {
            RpcCall(UNPARSED, NONE) // 본문이 JSON-RPC 가 아니어도 감사 행은 남긴다
        }
    }

    private data class RpcCall(val method: String, val tool: String)

    companion object {
        const val TYPE = "mcp_request"
        const val MCP_PATH = "/mcp"
        const val ANONYMOUS = "anonymous"
        private const val NONE = "none"
        private const val UNPARSED = "unparsed"
        private const val BODY_CACHE_LIMIT = 64 * 1024
    }
}
