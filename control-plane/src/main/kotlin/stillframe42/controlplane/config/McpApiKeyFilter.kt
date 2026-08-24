package stillframe42.controlplane.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.security.MessageDigest
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.filter.OncePerRequestFilter

/**
 * /mcp 전용 API Key 인증 필터 (DAY 16) — OAuth 2.1 전환 전까지의 임시 인증.
 *
 * MCP Streamable HTTP 는 왕복마다 무상태 HTTP 요청이므로 "요청마다 헤더 검사"로 충분하다
 * (세션 상태 기반 인증 불필요 — DAY 15 실측 노트).
 */
class McpApiKeyFilter(apiKey: String) : OncePerRequestFilter() {

    private val expected = apiKey.toByteArray(Charsets.UTF_8)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val provided = request.getHeader(API_KEY_HEADER)
        // MessageDigest.isEqual — 상수 시간 비교 (타이밍 부채널 방지)
        if (provided != null && MessageDigest.isEqual(expected, provided.toByteArray(Charsets.UTF_8))) {
            filterChain.doFilter(request, response)
            return
        }
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = "application/json;charset=UTF-8"
        response.writer.write("""{"error":"unauthorized","message":"$API_KEY_HEADER 헤더가 없거나 일치하지 않는다"}""")
    }

    companion object {
        /** agent-service 의 mcp_tools.API_KEY_HEADER 와 계약 — 변경 시 양쪽 동시 수정 */
        const val API_KEY_HEADER = "X-API-Key"
    }
}

@Configuration
class McpSecurityConfig {

    /** 키 미설정(빈 값)이면 등록 자체를 비활성 — 로컬 개발은 양쪽 무설정으로 동작 (키-게이트 관례). */
    @Bean
    fun mcpApiKeyFilterRegistration(
        @Value("\${ops.mcp.api-key:}") apiKey: String,
    ): FilterRegistrationBean<McpApiKeyFilter> =
        FilterRegistrationBean(McpApiKeyFilter(apiKey)).apply {
            addUrlPatterns("/mcp", "/mcp/*")
            isEnabled = apiKey.isNotBlank()
        }
}
