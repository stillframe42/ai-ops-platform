package stillframe42.controlplane.approval.controller

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import stillframe42.controlplane.approval.model.ApprovalDecisionOutcome
import stillframe42.controlplane.approval.model.ApprovalStatus
import stillframe42.controlplane.approval.service.ActionApprovalService
import stillframe42.controlplane.approval.service.DecisionPublishFailedException

/**
 * 승인 API (DAY 22, ADR-0005) — curl/테스트 경로. Slack 버튼(Phase 3)도 같은 서비스
 * decide 로 수렴한다 — 이 컨트롤러는 입력 채널 어댑터일 뿐 (ADR-0006).
 */
@RestController
@RequestMapping("/api/incidents")
class ApprovalController(private val service: ActionApprovalService) {

    @PostMapping("/{incidentId}/approve")
    fun approve(
        @PathVariable incidentId: String,
        @RequestBody(required = false) body: DecisionRequest?,
    ): DecisionResponse = decide(incidentId, ApprovalStatus.APPROVED, body)

    @PostMapping("/{incidentId}/reject")
    fun reject(
        @PathVariable incidentId: String,
        @RequestBody(required = false) body: DecisionRequest?,
    ): DecisionResponse = decide(incidentId, ApprovalStatus.REJECTED, body)

    private fun decide(incidentId: String, status: String, body: DecisionRequest?): DecisionResponse {
        val decidedBy = body?.decidedBy?.takeIf { it.isNotBlank() } ?: DEFAULT_DECIDER
        val outcome = try {
            service.decide(incidentId, status, decidedBy)
        } catch (e: DecisionPublishFailedException) {
            // 전이는 롤백됐다 — 클라이언트가 그대로 재시도하면 된다
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.message, e)
        }
        return when (outcome) {
            is ApprovalDecisionOutcome.NotFound ->
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "승인 요청 없음: $incidentId")
            is ApprovalDecisionOutcome.AlreadyDecided ->
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "이미 종결된 승인 (status=${outcome.status}): $incidentId",
                )
            is ApprovalDecisionOutcome.Decided -> DecisionResponse.from(outcome)
        }
    }

    companion object {
        private const val DEFAULT_DECIDER = "api"
    }
}
