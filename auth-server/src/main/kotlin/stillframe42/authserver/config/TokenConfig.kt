package stillframe42.authserver.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer
import stillframe42.authserver.token.AudienceClaimCustomizer

/** 토큰 클레임 배선 — 서명 키(JWK)는 Boot 자동구성이 기동 시 생성 (replica 1 전제, ADR-0016) */
@Configuration
class TokenConfig {

    @Bean
    fun audienceClaimCustomizer(): OAuth2TokenCustomizer<JwtEncodingContext> = AudienceClaimCustomizer()
}
