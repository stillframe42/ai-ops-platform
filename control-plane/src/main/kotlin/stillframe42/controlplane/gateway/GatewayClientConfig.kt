package stillframe42.controlplane.gateway

import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository

/**
 * control-plane 의 OAuth 클라이언트 배선 (ADR-0016) — 리소스 서버 체인과 별개로, 게이트웨이 호출에만 쓰인다.
 * 서블릿 요청 컨텍스트 밖(시드 로더·MCP 도구 스레드)에서도 동작해야 하므로 서비스 기반 매니저를 쓴다.
 */
@Configuration
class GatewayClientConfig {

    @Bean
    fun oAuth2AuthorizedClientManager(
        clientRegistrationRepository: ClientRegistrationRepository,
        oAuth2AuthorizedClientService: OAuth2AuthorizedClientService,
    ): OAuth2AuthorizedClientManager =
        AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrationRepository, oAuth2AuthorizedClientService).apply {
            setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build())
        }

    @Bean
    fun gatewayTokenCustomizer(gatewayTokenSource: GatewayTokenSource): OpenAiHttpClientBuilderCustomizer =
        OpenAiHttpClientBuilderCustomizer { it.interceptor(GatewayTokenInterceptor(gatewayTokenSource)) }
}
