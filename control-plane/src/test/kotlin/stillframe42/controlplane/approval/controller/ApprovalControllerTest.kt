package stillframe42.controlplane.approval.controller

import java.time.Instant
import kotlin.test.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import stillframe42.controlplane.alert.event.EventPublisher
import stillframe42.controlplane.approval.model.ActionApprovalRequest
import stillframe42.controlplane.approval.model.ApprovalCard
import stillframe42.controlplane.approval.model.ApprovalStatus
import stillframe42.controlplane.approval.model.PendingApproval
import stillframe42.controlplane.approval.model.SlackMessageRef
import stillframe42.controlplane.approval.repository.ActionApprovalRepository
import stillframe42.controlplane.approval.service.ActionApprovalService

/**
 * 단위 테스트 경계 — standalone MockMvc + fake 저장소·발행기 (실 서비스 경유,
 * IncidentQueryController 테스트와 같은 관례). 와이어(snake_case)와 404/409/503 규약만 검증.
 */
class ApprovalControllerTest {

    private class FakeRepository(
        private val transitioned: Boolean = true,
        private val latestStatus: String? = null,
    ) : ActionApprovalRepository {
        override fun insertPendingIfAbsent(request: ActionApprovalRequest): Boolean = true
        override fun markDecided(
            incidentId: String,
            status: String,
            decidedBy: String,
            decidedAt: Instant,
        ): Boolean = transitioned

        override fun findLatestStatus(incidentId: String): String? = latestStatus
        override fun recordSlackMessage(incidentId: String, message: SlackMessageRef): Boolean = false
        override fun markReminded(incidentId: String, remindedAt: Instant): Boolean = false
        override fun findPendingRequestedBefore(cutoff: Instant): List<PendingApproval> = emptyList()
        override fun findLatestCard(incidentId: String): ApprovalCard? = null
    }

    private val incidentId = "inc-memory-pressure-20260801100000-ab12cd"

    private fun mvc(
        transitioned: Boolean = true,
        latestStatus: String? = null,
        publishAccepted: Boolean = true,
    ) = MockMvcBuilders.standaloneSetup(
        ApprovalController(
            ActionApprovalService(
                FakeRepository(transitioned, latestStatus),
                EventPublisher { _, _, _ -> publishAccepted },
                ApplicationEventPublisher { },
            ),
        ),
    ).build()

    @Test
    fun `승인은 전이 결과를 snake_case 로 반환한다`() {
        mvc().perform(
            post("/api/incidents/$incidentId/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"decided_by": "U0123ABC"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.incident_id").value(incidentId))
            .andExpect(jsonPath("$.status").value(ApprovalStatus.APPROVED))
            .andExpect(jsonPath("$.decided_by").value("U0123ABC"))
            .andExpect(jsonPath("$.decided_at").isString)
    }

    @Test
    fun `본문 없는 승인의 주체는 api - curl 테스트 경로 기본값`() {
        mvc().perform(post("/api/incidents/$incidentId/approve"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.decided_by").value("api"))
    }

    @Test
    fun `거부는 rejected 로 전이한다`() {
        mvc().perform(post("/api/incidents/$incidentId/reject"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value(ApprovalStatus.REJECTED))
    }

    @Test
    fun `승인 요청이 없는 인시던트는 404`() {
        mvc(transitioned = false, latestStatus = null)
            .perform(post("/api/incidents/inc-unknown/approve"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `이미 결정된 승인은 409`() {
        mvc(transitioned = false, latestStatus = ApprovalStatus.APPROVED)
            .perform(post("/api/incidents/$incidentId/reject"))
            .andExpect(status().isConflict)
    }

    @Test
    fun `만료된 승인도 409 - 대기 시한이 지난 결정은 무효`() {
        mvc(transitioned = false, latestStatus = ApprovalStatus.EXPIRED)
            .perform(post("/api/incidents/$incidentId/approve"))
            .andExpect(status().isConflict)
    }

    @Test
    fun `decisions 발행 실패는 503 - 재시도 가능 신호`() {
        mvc(publishAccepted = false)
            .perform(post("/api/incidents/$incidentId/approve"))
            .andExpect(status().isServiceUnavailable)
    }
}
