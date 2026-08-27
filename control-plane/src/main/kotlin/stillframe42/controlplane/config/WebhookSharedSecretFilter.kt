package stillframe42.controlplane.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.security.MessageDigest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Alertmanager 웹훅 전용 공유 시크릿 인증 (ADR-0016) — `Authorization: Bearer <secret>` 를 정적 대조한다.
 * Alertmanager 는 OAuth 클라이언트가 될 수 없어(Client Credentials 미지원) JWT 경로 대신 이 필터가 담당.
 *
 * 일치하면 인증 컨텍스트만 채우고 판정은 인가 규칙에 맡긴다 — 불일치 시 직접 401 을 쓰지 않는 이유는
 * 401 응답 형식을 리소스 서버 체인과 같은 Security 진입점으로 일원화하기 위함.
 */
class WebhookSharedSecretFilter(sharedSecret: String) : OncePerRequestFilter() {

    private val expected = sharedSecret.toByteArray(Charsets.UTF_8)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val provided = request.getHeader("Authorization")
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)
            ?.toByteArray(Charsets.UTF_8)
        // MessageDigest.isEqual — 상수 시간 비교 (타이밍 부채널 방지)
        if (provided != null && MessageDigest.isEqual(expected, provided)) {
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken.authenticated(PRINCIPAL, null, listOf(SimpleGrantedAuthority(AUTHORITY)))
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        const val PRINCIPAL = "alertmanager"
        const val AUTHORITY = "WEBHOOK_ALERTMANAGER"
        private const val BEARER_PREFIX = "Bearer "
    }
}
