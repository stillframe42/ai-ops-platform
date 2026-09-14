package stillframe42.controlplane.config

import kotlin.test.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import stillframe42.controlplane.alert.controller.AlertmanagerWebhookController
import stillframe42.controlplane.alert.service.AlertIngestService
import stillframe42.controlplane.approval.controller.ApprovalController
import stillframe42.controlplane.approval.model.ApprovalDecisionOutcome
import stillframe42.controlplane.approval.service.ActionApprovalService
import stillframe42.controlplane.evaluation.controller.EvaluationReviewController
import stillframe42.controlplane.evaluation.model.EvaluationReview
import stillframe42.controlplane.evaluation.model.ReviewOutcome
import stillframe42.controlplane.evaluation.model.ReviewStatus
import stillframe42.controlplane.evaluation.service.IncidentEvaluationService
import stillframe42.controlplane.incident.controller.IncidentQueryController
import stillframe42.controlplane.incident.service.IncidentReportService

/**
 * 인가 규칙 테스트 (ADR-0016) — 스코프 ↔ 엔드포인트 매핑을 고정한다. 핵심은 "에이전트 토큰(ops:read)으로
 * 승인 API 는 403" (레드팀 RT 직접 경로 — 승인 권한의 구조적 부재).
 *
 * 컨텍스트 경계: WebMvcTest 슬라이스 + SecurityConfig. JWT 는 `jwt()` 후처리기가 인증 컨텍스트를 직접
 * 넣으므로 issuer 접근이 없다 — JwtDecoder 빈은 실 토큰 경로(무토큰·위조 토큰)에서만 호출되는 스텁.
 */
@WebMvcTest(
    controllers = [
        ApprovalController::class,
        IncidentQueryController::class,
        AlertmanagerWebhookController::class,
        EvaluationReviewController::class,
    ],
)
@Import(SecurityConfig::class, SecurityConfigTest.JwtStub::class)
@TestPropertySource(properties = ["ops.webhook.shared-secret=test-webhook-secret"])
class SecurityConfigTest(@Autowired private val mvc: MockMvc) {

    @TestConfiguration
    class JwtStub {
        @Bean
        fun jwtDecoder(): JwtDecoder = // BadJwtException 이어야 401 로 변환된다 — 그 외 JwtException 은 서비스 오류(500 계열)로 전파
        JwtDecoder { throw BadJwtException("테스트 스텁 — 실 토큰 검증 없음") }
    }

    @MockitoBean
    private lateinit var actionApprovalService: ActionApprovalService

    @MockitoBean
    private lateinit var incidentReportService: IncidentReportService

    @MockitoBean
    private lateinit var alertIngestService: AlertIngestService

    @MockitoBean
    private lateinit var incidentEvaluationService: IncidentEvaluationService

    private val incidentId = "inc-error-rate-surge-20260826031500-a1b2c3"

    private fun agentToken() = jwt().authorities(SimpleGrantedAuthority(SecurityConfig.SCOPE_OPS_READ))
    private fun adminToken() = jwt().authorities(
        SimpleGrantedAuthority(SecurityConfig.SCOPE_OPS_APPROVE),
        SimpleGrantedAuthority(SecurityConfig.SCOPE_OPS_READ),
    )

    // --- 승인 API: ops:approve 만 ---

    @Test
    fun `에이전트 토큰(ops read)으로 승인 API 는 403 이다`() {
        mvc.perform(post("/api/incidents/$incidentId/approve").with(agentToken()))
            .andExpect(status().isForbidden)
        mvc.perform(post("/api/incidents/$incidentId/reject").with(agentToken()))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `운영자 토큰(ops approve)으로 승인 API 는 인가를 통과한다`() {
        `when`(actionApprovalService.decide(anyString(), anyString(), anyString())).thenReturn(ApprovalDecisionOutcome.NotFound)

        // 404 = 인가를 지나 컨트롤러까지 도달했다는 증거 (서비스 스텁이 미존재로 응답)
        mvc.perform(post("/api/incidents/$incidentId/approve").with(adminToken()))
            .andExpect(status().isNotFound)
    }

    // --- 리뷰 API: 큐 조회는 ops:read, 검토 기록은 ops:approve ---

    @Test
    fun `리뷰 큐는 ops read 로 통과하고 검토 기록은 ops read 만으로는 403 이다`() {
        // 큐 조회 스텁 불필요 — Mockito 기본 반환(빈 목록)으로 200

        mvc.perform(get("/api/evaluations/review-queue").with(agentToken())).andExpect(status().isOk)
        mvc.perform(
            post("/api/evaluations/1/review").with(agentToken())
                .contentType(MediaType.APPLICATION_JSON).content("""{"status": "dismissed"}"""),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `운영자 토큰(ops approve)으로 검토 기록은 인가를 통과한다`() {
        // 매처 대신 실값 — Kotlin 비-null 파라미터에 any() 는 null 을 넘겨 호출 지점에서 깨진다
        `when`(incidentEvaluationService.review(1L, EvaluationReview(ReviewStatus.DISMISSED, null, null, null, "api")))
            .thenReturn(ReviewOutcome.NotFound)

        // 404 = 인가를 지나 컨트롤러까지 도달했다는 증거 (승인 API 테스트와 같은 판정)
        mvc.perform(
            post("/api/evaluations/1/review").with(adminToken())
                .contentType(MediaType.APPLICATION_JSON).content("""{"status": "dismissed"}"""),
        ).andExpect(status().isNotFound)
    }

    // --- 조회 API·MCP: ops:read ---

    @Test
    fun `조회 API 는 ops read 토큰으로 통과하고 무토큰은 401 이다`() {
        `when`(incidentReportService.findRecent(anyInt())).thenReturn(emptyList())

        mvc.perform(get("/api/incidents").with(agentToken())).andExpect(status().isOk)
        mvc.perform(get("/api/incidents"))
            .andExpect(status().isUnauthorized)
            .andExpect(header().exists("WWW-Authenticate"))
    }

    @Test
    fun `MCP 경로는 ops read 가 없는 토큰이면 403 이다`() {
        val approveOnly = jwt().authorities(SimpleGrantedAuthority(SecurityConfig.SCOPE_OPS_APPROVE))

        mvc.perform(post("/mcp").with(approveOnly)).andExpect(status().isForbidden)
        mvc.perform(post("/mcp")).andExpect(status().isUnauthorized)
        // 슬라이스에는 MCP 핸들러가 없다 — 404 는 인가 통과 후 라우팅 부재, 401/403 이 아님이 요점
        mvc.perform(post("/mcp").with(agentToken())).andExpect(status().isNotFound)
    }

    @Test
    fun `서명 검증에 실패하는 실 bearer 토큰은 401 이다`() {
        mvc.perform(get("/api/incidents").header("Authorization", "Bearer forged.token.value"))
            .andExpect(status().isUnauthorized)
    }

    // --- 웹훅: 공유 시크릿 ---

    @Test
    fun `웹훅은 공유 시크릿 bearer 로만 202 이다`() {
        val body = """{"version":"4","alerts":[]}"""

        mvc.perform(
            post("/webhook/alertmanager").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer $WEBHOOK_SECRET"),
        ).andExpect(status().isAccepted)
        mvc.perform(post("/webhook/alertmanager").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized)
        mvc.perform(
            post("/webhook/alertmanager").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer wrong-secret"),
        ).andExpect(status().isUnauthorized)
        // 웹훅 체인에는 JWT 필터가 없다 — 실 JWT bearer 도 "wrong-secret" 과 같이 문자열 대조 실패 401 이다.
        // jwt() 후처리기는 인증 컨텍스트를 직접 주입해 실 경로를 재현하지 못하므로 여기서는 쓰지 않는다
    }

    // --- probe·스크레이프 ---

    @Test
    fun `actuator probe 는 토큰 없이 통과한다`() {
        // 슬라이스에 actuator 가 없어 404 — 401 이 아니면 permitAll 이 적용된 것
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isNotFound)
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isNotFound)
    }

    companion object {
        /** @TestPropertySource 의 값과 같은 문자열 — 어노테이션은 외부 상수 참조가 안 된다 */
        private const val WEBHOOK_SECRET = "test-webhook-secret"
    }
}
