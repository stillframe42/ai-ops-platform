package stillframe42.authserver.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer
import org.springframework.security.web.SecurityFilterChain

/**
 * 필터 체인 2개 — 인가 서버 엔드포인트(oauth2 경로)와 그 외.
 * Boot 기본 체인(폼 로그인 + 생성 비밀번호 사용자)을 쓰지 않는 이유: 사람 로그인이 없는 M2M 전용이라
 * 로그인 표면 자체를 두지 않는다. 그 외 경로는 actuator probe·스크레이프만 열고 전부 거부.
 */
@Configuration
class SecurityConfig {

    @Bean
    @Order(1)
    fun authorizationServerSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        // Security 7 DSL — configurer 를 먼저 등록해야 endpointsMatcher(/oauth2 계열 경로)를 꺼낼 수 있다
        http.oauth2AuthorizationServer { }
        val endpointsMatcher = http.getConfigurer(OAuth2AuthorizationServerConfigurer::class.java)!!.endpointsMatcher
        http
            .securityMatcher(endpointsMatcher)
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .csrf { it.ignoringRequestMatchers(endpointsMatcher) }
        return http.build()
    }

    @Bean
    @Order(2)
    fun managementSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests {
                it.requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                    .anyRequest().denyAll()
            }
            .csrf { it.disable() }
        return http.build()
    }
}
