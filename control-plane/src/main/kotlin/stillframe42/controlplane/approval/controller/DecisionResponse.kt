package stillframe42.controlplane.approval.controller

import com.fasterxml.jackson.annotation.JsonProperty
import stillframe42.controlplane.approval.model.ApprovalDecisionOutcome

/** 승인 결정 응답 와이어 (snake_case) — 시각은 ISO-8601 문자열 (직렬화기 기본 포맷 미의존, 응답 DTO 관례) */
data class DecisionResponse(
    @field:JsonProperty("incident_id") val incidentId: String,
    @field:JsonProperty("status") val status: String,
    @field:JsonProperty("decided_by") val decidedBy: String,
    @field:JsonProperty("decided_at") val decidedAt: String,
) {

    companion object {
        fun from(outcome: ApprovalDecisionOutcome.Decided) = DecisionResponse(
            incidentId = outcome.incidentId,
            status = outcome.status,
            decidedBy = outcome.decidedBy,
            decidedAt = outcome.decidedAt.toString(),
        )
    }
}
