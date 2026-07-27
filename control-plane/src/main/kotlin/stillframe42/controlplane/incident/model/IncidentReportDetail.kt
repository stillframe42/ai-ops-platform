package stillframe42.controlplane.incident.model

/** 단건 조회용 — 요약 + 보고서 원문(jsonb 문자열). */
data class IncidentReportDetail(
    val summary: IncidentReportSummary,
    val report: String,
)
