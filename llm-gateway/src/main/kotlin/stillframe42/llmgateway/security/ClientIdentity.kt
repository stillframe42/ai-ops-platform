package stillframe42.llmgateway.security

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * 요청 주체의 client_id — 예산·rate limit·비용 원장·감사 로그의 서비스 차원.
 * Client Credentials 토큰의 `sub` 가 곧 client_id (Spring Authorization Server 기본 클레임).
 * 인증이 없는 경로(permitAll)에서는 [ANONYMOUS] — `/v1` 하위는 인가가 먼저 막으므로 도달하지 않는다.
 */
object ClientIdentity {

    const val ANONYMOUS = "anonymous"

    fun current(): String = (SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken)
        ?.token?.subject ?: ANONYMOUS

    fun currentScopes(): String = (SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken)
        ?.token?.getClaimAsStringList("scope")?.joinToString(" ").orEmpty()
}
