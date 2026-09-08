package stillframe42.llmgateway.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.observation.SecurityObservationSettings
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

    /**
     * Spring Security 관측 범위 축소 (DAY 44) — 빈이 없으면 요청 체인(`security filterchain before/after`·`secured request`)·
     * 인가·인증이 전부 스팬이라 요청당 4~5 스팬이 붙는다 (9/8 실측: control-plane 스팬 140 중 117). 실지연이 있는
     * 인증(JWT 검증·JWKS 조회)만 남긴다 — 나머지는 생성·전송 비용만 남기는 노이즈라 Collector 필터가 아닌 원천에서 끈다.
     */
    @Bean
    fun securityObservationSettings(): SecurityObservationSettings =
        SecurityObservationSettings.withDefaults().shouldObserveAuthorizations(false).build()

    companion object {
        const val SCOPE_LLM_INVOKE = "SCOPE_llm:invoke"
    }
}
