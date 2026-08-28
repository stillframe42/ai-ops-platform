package stillframe42.controlplane.gateway

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class GatewayTokenInterceptorTest {

    private val tokenSource = FakeTokenSource()
    private val interceptor = GatewayTokenInterceptor(tokenSource)

    @Test
    fun `SDK 가 넣은 api-key bearer 를 OAuth 토큰으로 교체한다`() {
        val chain = FakeChain(statuses = listOf(200))

        interceptor.intercept(chain)

        assertEquals(listOf<String?>("Bearer token-1"), chain.sentAuthorizations)
        assertEquals(0, tokenSource.evictions)
    }

    @Test
    fun `401 이면 캐시를 비우고 재발급 토큰으로 1회 재시도한다`() {
        val chain = FakeChain(statuses = listOf(401, 200))

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
        assertEquals(listOf<String?>("Bearer token-1", "Bearer token-2"), chain.sentAuthorizations)
        assertEquals(1, tokenSource.evictions)
    }

    @Test
    fun `재시도도 401 이면 그대로 반환한다 - 무한 재시도 없음`() {
        val chain = FakeChain(statuses = listOf(401, 401))

        val response = interceptor.intercept(chain)

        assertEquals(401, response.code)
        assertEquals(2, chain.sentAuthorizations.size)
    }

    private class FakeTokenSource : TokenSource {
        private var issued = 0
        var evictions = 0
        private var current: String? = null

        override fun token(): String = current ?: "token-${++issued}".also { current = it }
        override fun evict() { evictions++; current = null }
    }

    /** SDK 가 설정한 api-key bearer 로 시작하는 요청을 흉내 낸다 */
    private class FakeChain(private val statuses: List<Int>) : Interceptor.Chain {
        val sentAuthorizations = mutableListOf<String?>()
        private var calls = 0
        private val original = Request.Builder().url("http://llm-gateway:8090/v1/embeddings")
            .header("Authorization", "Bearer gateway-token").build()

        override fun request(): Request = original
        override fun proceed(request: Request): Response {
            sentAuthorizations += request.header("Authorization")
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(statuses[calls++])
                .message("").body("".toResponseBody()).build()
        }
        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
}
