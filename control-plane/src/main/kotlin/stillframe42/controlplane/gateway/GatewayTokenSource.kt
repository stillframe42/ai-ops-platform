package stillframe42.controlplane.gateway

import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.stereotype.Component

/**
 * llm-gateway 호출용 액세스 토큰 공급 (ADR-0016) — Spring Security 의 AuthorizedClientManager 가
 * Client Credentials 발급·캐시·만료 60초 전(clockSkew 기본값) 재발급을 수행한다. 여기서는 등록 id 와
 * "캐시 비우기"만 감싼다 — 401 수신 시 재발급 경로가 캐시된 토큰을 다시 쓰지 않게.
 */
@Component
class GatewayTokenSource(
    private val oAuth2AuthorizedClientManager: OAuth2AuthorizedClientManager,
    private val oAuth2AuthorizedClientService: OAuth2AuthorizedClientService,
) : TokenSource {

    override fun token(): String {
        val client = oAuth2AuthorizedClientManager.authorize(
            OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION_ID).principal(PRINCIPAL).build(),
        ) ?: throw IllegalStateException("llm-gateway 토큰 발급 실패 — 등록 $REGISTRATION_ID 의 인가 결과 없음")
        return client.accessToken.tokenValue
    }

    override fun evict() {
        oAuth2AuthorizedClientService.removeAuthorizedClient(REGISTRATION_ID, PRINCIPAL)
    }

    companion object {
        const val REGISTRATION_ID = "llm-gateway"
        /** M2M 이라 사용자 주체가 없다 — 캐시 키로만 쓰이는 고정 이름 */
        const val PRINCIPAL = "control-plane"
    }
}
