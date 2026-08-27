package stillframe42.controlplane.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.security.web.authentication.HttpStatusEntryPoint

/**
 * OAuth2 리소스 서버 (ADR-0016) — auth-server 가 발급한 JWT 를 issuer JWKS 로 자체 검증하고
 * 스코프로 인가한다. 스코프 ↔ 엔드포인트 매핑이 곧 도구 단위 인가 표:
 *
 * | 경로 | 요구 |
 * |------|------|
 * | `/mcp` (조회 도구 3종) | `ops:read` |
 * | `GET /api/incidents` 계열 | `ops:read` |
 * | `POST /api/incidents/{id}/approve·reject` | `ops:approve` — agent-service 토큰에는 구조적으로 없다 (LLM06) |
 * | `POST /webhook/alertmanager` | 공유 시크릿 (별도 체인) |
 * | actuator probe·스크레이프 | permitAll |
 *
 * 무인증 상태는 두지 않는다 — issuer 는 기본값이 있어도 검증은 항상 수행되고, 웹훅 시크릿은 미설정 시 기동 실패.
 * 세션·CSRF 는 전부 끈다 (M2M bearer 전용 — 브라우저 세션이 없다).
 */
@Configuration
class SecurityConfig {

    @Bean
    @Order(1)
    fun webhookSecurityFilterChain(
        http: HttpSecurity,
        @Value("\${ops.webhook.shared-secret}") sharedSecret: String,
    ): SecurityFilterChain {
        http
            .securityMatcher(WEBHOOK_PATH)
            .addFilterBefore(WebhookSharedSecretFilter(sharedSecret), AuthorizationFilter::class.java)
            .authorizeHttpRequests { it.anyRequest().hasAuthority(WebhookSharedSecretFilter.AUTHORITY) }
            // 시크릿 부재·불일치 = 인증 실패 401 (기본은 익명 접근 거부 403 — 리소스 서버 체인과 의미를 맞춘다)
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .csrf { it.disable() }
        return http.build()
    }

    @Bean
    @Order(2)
    fun resourceServerSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests {
                it.requestMatchers("/actuator/health/**", "/actuator/prometheus", "/actuator/metrics/**", "/actuator/info").permitAll()
                    .requestMatchers("/mcp/**").hasAuthority(SCOPE_OPS_READ)
                    .requestMatchers(HttpMethod.POST, "/api/incidents/*/approve", "/api/incidents/*/reject").hasAuthority(SCOPE_OPS_APPROVE)
                    .requestMatchers(HttpMethod.GET, "/api/incidents/**").hasAuthority(SCOPE_OPS_READ)
                    .anyRequest().authenticated()
            }
            // Boot 자동구성의 JwtDecoder(issuer-uri·audiences) 사용 — scope 클레임은 기본 변환대로 SCOPE_ 접두 권한이 된다
            .oauth2ResourceServer { it.jwt(Customizer.withDefaults()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .csrf { it.disable() }
        return http.build()
    }

    companion object {
        const val WEBHOOK_PATH = "/webhook/alertmanager"
        const val SCOPE_OPS_READ = "SCOPE_ops:read"
        const val SCOPE_OPS_APPROVE = "SCOPE_ops:approve"
    }
}
