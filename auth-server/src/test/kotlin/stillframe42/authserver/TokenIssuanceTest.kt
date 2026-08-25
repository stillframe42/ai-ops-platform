package stillframe42.authserver

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Client Credentials 발급 계약 — 스코프·aud·수명이 설정과 일치하고, 권한 밖 스코프·잘못된 시크릿은 거부된다.
 * 시크릿 env 는 테스트 프로퍼티로 주입 (미설정 = 기동 실패가 정상 동작이라 기본값이 없다)
 */
@SpringBootTest(
    properties = [
        "AUTH_CLIENT_SECRET_AGENT_SERVICE=agent-secret",
        "AUTH_CLIENT_SECRET_CONTROL_PLANE=cp-secret",
        "AUTH_CLIENT_SECRET_OPS_ADMIN=admin-secret",
    ],
)
@AutoConfigureMockMvc
class TokenIssuanceTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jwtDecoder: JwtDecoder,
) {
    private val jsonMapper = JsonMapper()

    private fun issue(clientId: String, secret: String, scope: String) =
        mockMvc.perform(
            post("/oauth2/token")
                .with(httpBasic(clientId, secret))
                .param("grant_type", "client_credentials")
                .param("scope", scope),
        )

    private fun accessToken(clientId: String, secret: String, scope: String): String {
        val body = issue(clientId, secret, scope).andExpect(status().isOk).andReturn().response.contentAsString
        return jsonMapper.readTree(body)["access_token"].asString()
    }

    @Test
    fun `agent-service 토큰은 ops·llm 스코프와 두 리소스의 aud 를 가진다`() {
        val jwt = jwtDecoder.decode(accessToken("agent-service", "agent-secret", "ops:read llm:invoke"))

        assertEquals("agent-service", jwt.subject)
        assertEquals(setOf("ops:read", "llm:invoke"), jwt.getClaimAsStringList("scope")!!.toSet())
        assertEquals(setOf("control-plane", "llm-gateway"), jwt.audience!!.toSet())
    }

    @Test
    fun `토큰 수명은 15분`() {
        val jwt = jwtDecoder.decode(accessToken("agent-service", "agent-secret", "ops:read"))

        assertEquals(Duration.ofMinutes(15), Duration.between(jwt.issuedAt, jwt.expiresAt))
    }

    @Test
    fun `control-plane 토큰의 aud 는 llm-gateway 뿐`() {
        val jwt = jwtDecoder.decode(accessToken("control-plane", "cp-secret", "llm:invoke"))

        assertEquals(listOf("llm-gateway"), jwt.audience)
    }

    @Test
    fun `agent-service 는 ops-approve 를 요청할 수 없다 - invalid_scope`() {
        issue("agent-service", "agent-secret", "ops:approve")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_scope"))
    }

    @Test
    fun `잘못된 시크릿은 401`() {
        issue("agent-service", "wrong", "ops:read").andExpect(status().isUnauthorized)
    }

    @Test
    fun `JWKS 는 기동 시 생성된 키 1개를 노출한다`() {
        mockMvc.perform(get("/oauth2/jwks"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.keys.length()").value(1))
    }

    @Test
    fun `인가 엔드포인트 밖은 probe 만 열려 있다`() {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk)
        val other = mockMvc.perform(get("/anything")).andReturn().response.status
        assertTrue(other in setOf(401, 403), "예상 밖 상태: $other")
    }
}
