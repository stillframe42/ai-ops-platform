package stillframe42.llmgateway.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

/**
 * OAuth2 리소스 서버 (ADR-0016) — control-plane 과 같은 issuer 의 JWKS 로 JWT 를 자체 검증한다.
 * `/v1` 하위 전부 `llm:invoke` 스코프 요구, 서비스 식별은 헤더 자기 신고가 아니라 검증된 토큰의 `sub`(client_id).
 * 세션·CSRF 는 전부 끈다 (M2M bearer 전용).
 */
@Configuration
class SecurityConfig {

    @Bean
    fun resourceServerSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests {
                it.requestMatchers("/actuator/health/**", "/actuator/prometheus", "/actuator/metrics/**", "/actuator/info").permitAll()
                    .requestMatchers("/v1/**").hasAuthority(SCOPE_LLM_INVOKE)
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { it.jwt(Customizer.withDefaults()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .csrf { it.disable() }
        return http.build()
    }

    companion object {
        const val SCOPE_LLM_INVOKE = "SCOPE_llm:invoke"
    }
}
