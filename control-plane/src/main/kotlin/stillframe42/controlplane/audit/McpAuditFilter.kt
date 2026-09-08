package stillframe42.controlplane.audit

import io.opentelemetry.api.trace.Span
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
 *
 * 같은 필드를 현재 SERVER 스팬(`http post /mcp`)의 속성으로도 부여한다 (DAY 44, docs/otel-genai-mapping.md §3 MCP 서버 행) —
 * 식별은 플랫폼 확장 `aiops.client_id`·`aiops.scope`, 프로토콜은 MCP 컨벤션의 표준 키(`mcp.method.name`·`gen_ai.tool.name`·
 * `jsonrpc.request.id`·`client.address`). 도구 본체 스팬(`tools/call {tool}`, McpToolMetrics)에서는 닿지 않는 요청 계층 값이라
 * 여기(HTTP 서버 스팬)에 둔다. 스팬이 없으면 `Span.current()` 는 무효 스팬이라 부여가 조용히 무시된다.
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
        val clientId = jwt?.subject ?: ANONYMOUS
        val scopes = jwt?.getClaimAsStringList("scope")?.joinToString(" ").orEmpty()
        val fields = mapOf(
            "client_id" to clientId,
            "scope" to scopes,
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
        promoteToSpan(clientId, scopes, rpc, request.remoteAddr)
    }

    private fun promoteToSpan(clientId: String, scopes: String, rpc: RpcCall, clientAddress: String?) {
        val span = Span.current()
        span.setAttribute(ATTR_CLIENT_ID, clientId)
        if (scopes.isNotEmpty()) span.setAttribute(ATTR_SCOPE, scopes)
        if (rpc.method != NONE && rpc.method != UNPARSED) span.setAttribute(ATTR_MCP_METHOD_NAME, rpc.method)
        if (rpc.tool != NONE) span.setAttribute(ATTR_TOOL_NAME, rpc.tool)
        rpc.id?.let { span.setAttribute(ATTR_JSONRPC_REQUEST_ID, it) }
        clientAddress?.let { span.setAttribute(ATTR_CLIENT_ADDRESS, it) }
    }

    private fun parseRpc(body: ByteArray): RpcCall {
        if (body.isEmpty()) return RpcCall(NONE, NONE)
        return try {
            val node = mapper.readTree(body)
            val method = node.path("method").asString().ifEmpty { NONE }
            val tool = node.path("params").path("name").asString().ifEmpty { NONE }
            val id = node.path("id").asString().ifEmpty { null }
            RpcCall(method, tool, id)
        } catch (_: Exception) {
            RpcCall(UNPARSED, NONE) // 본문이 JSON-RPC 가 아니어도 감사 행은 남긴다
        }
    }

    private data class RpcCall(val method: String, val tool: String, val id: String? = null)

    companion object {
        const val TYPE = "mcp_request"
        const val MCP_PATH = "/mcp"
        const val ANONYMOUS = "anonymous"
        private const val NONE = "none"
        private const val UNPARSED = "unparsed"
        private const val BODY_CACHE_LIMIT = 64 * 1024

        // 서버 스팬 속성명 — MCP 컨벤션 표준 키 + 플랫폼 확장 (docs/otel-genai-mapping.md §3·§5). 사용처가 이 필터뿐이라 여기 둔다
        private const val ATTR_CLIENT_ID = "aiops.client_id"
        private const val ATTR_SCOPE = "aiops.scope"
        private const val ATTR_MCP_METHOD_NAME = "mcp.method.name"
        private const val ATTR_TOOL_NAME = "gen_ai.tool.name"
        private const val ATTR_JSONRPC_REQUEST_ID = "jsonrpc.request.id"
        private const val ATTR_CLIENT_ADDRESS = "client.address"
    }
}
