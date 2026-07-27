package stillframe42.controlplane.incident.model

import java.time.Instant

/** 목록 조회용 요약 — V1__incident_reports.sql 의 추출 컬럼과 1:1. */
data class IncidentReportSummary(
    val incidentId: String,
    val scenario: String,
    val alertName: String?,
    val status: String,
    val severity: String?,
    val rootCause: String?,
    val confidence: Double?,
    val completedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
