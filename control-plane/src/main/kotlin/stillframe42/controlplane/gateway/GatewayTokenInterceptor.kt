package stillframe42.controlplane.gateway

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.slf4j.LoggerFactory

/**
 * 임베딩 호출의 Authorization 을 OAuth 토큰으로 교체한다 (ADR-0016). Spring AI 2.0 의 OpenAI 클라이언트는
 * 공식 openai-java SDK(OkHttp) 라 RestClient 인터셉터가 닿지 않는다 — SDK 가 설정한 `Bearer <api-key>` 를
 * OkHttp 인터셉터에서 덮어쓴다. 401 은 재발급 후 1회만 재시도 (agent-service ClientCredentialsAuth 와 같은 규칙).
 */
class GatewayTokenInterceptor(private val tokenSource: TokenSource) : Interceptor {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request().withBearer(tokenSource.token()))
        if (response.code != 401) return response
        // 재시도 전 응답 본문을 닫는다 — OkHttp 는 미소비 응답을 연결 누수로 취급한다
        response.close()
        logger.warn("llm-gateway 401 — 토큰 재발급 후 1회 재시도")
        tokenSource.evict()
        return chain.proceed(chain.request().withBearer(tokenSource.token()))
    }

    private fun Request.withBearer(token: String): Request =
        newBuilder().header("Authorization", "Bearer $token").build()
}
