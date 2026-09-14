package stillframe42.controlplane.incident.controller

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonUnwrapped
import stillframe42.controlplane.incident.model.IncidentReportSummary
import tools.jackson.databind.JsonNode


import stillframe42.controlplane.incident.event.IncidentEvent
/**
 * 조회 API 응답 모델 (DAY 19) — 와이어(snake_case)를 타입으로 고정한다.
 * 필드 추가·오타·누락이 컴파일 단계에서 걸린다 (IncidentEvent data class 와 같은 취지).
 * 시각은 ISO-8601 문자열로 변환해 내보낸다 — 직렬화기 기본 포맷에 의존하지 않는 결정적 표현.
 */
data class IncidentSummaryResponse(
    @JsonProperty("incident_id") val incidentId: String,
    @JsonProperty("scenario") val scenario: String,
    @JsonProperty("alert_name") val alertName: String?,
    @JsonProperty("status") val status: String,
    @JsonProperty("severity") val severity: String?,
    @JsonProperty("root_cause") val rootCause: String?,
    @JsonProperty("confidence") val confidence: Double?,
    @JsonProperty("completed_at") val completedAt: String?,
    @JsonProperty("created_at") val createdAt: String,
    @JsonProperty("updated_at") val updatedAt: String,
) {
    companion object {
        fun from(summary: IncidentReportSummary) = IncidentSummaryResponse(
            incidentId = summary.incidentId,
            scenario = summary.scenario,
            alertName = summary.alertName,
            status = summary.status,
            severity = summary.severity,
            rootCause = summary.rootCause,
            confidence = summary.confidence,
            completedAt = summary.completedAt?.toString(),
            createdAt = summary.createdAt.toString(),
            updatedAt = summary.updatedAt.toString(),
        )
    }
}

/** 단건 응답 — 요약 필드를 평탄화(@JsonUnwrapped)하고 보고서 원문을 JSON 객체로 더한다. */
data class IncidentDetailResponse(
    @field:JsonUnwrapped val summary: IncidentSummaryResponse,
    @JsonProperty("report") val report: JsonNode,
)
