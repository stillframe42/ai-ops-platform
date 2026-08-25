package stillframe42.authserver.token

import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer

/**
 * aud 클레임을 스코프 접두로 도출한다 — ops:* → control-plane, llm:* → llm-gateway.
 * 기본값(aud = client_id)은 토큰이 어느 리소스용인지 말해주지 않아 리소스 서버가 aud 검사를 할 수 없다.
 * 단일 토큰 다중 aud: 클라이언트는 토큰 1개만 관리하고, 각 리소스 서버는 자기 이름 포함 여부만 검사 (ADR-0016)
 */
class AudienceClaimCustomizer : OAuth2TokenCustomizer<JwtEncodingContext> {

    override fun customize(context: JwtEncodingContext) {
        if (context.tokenType != OAuth2TokenType.ACCESS_TOKEN) return
        val audiences = context.authorizedScopes
            .mapNotNull { RESOURCE_BY_SCOPE_PREFIX[it.substringBefore(':')] }
            .distinct()
        context.claims.audience(audiences)
    }

    companion object {
        val RESOURCE_BY_SCOPE_PREFIX = mapOf("ops" to "control-plane", "llm" to "llm-gateway")
    }
}
