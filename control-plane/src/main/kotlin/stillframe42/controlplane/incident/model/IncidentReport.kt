package stillframe42.controlplane.incident.model

import java.time.Instant
import java.time.OffsetDateTime
import stillframe42.controlplane.approval.model.ActionExecution
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * ops.analysis.results 수신 페이로드의 소비 측 표현 (DAY 19) — 발행 측은 agent-service
 * GraphRuntime.get_result. 목록 조회·Slack 포맷에 쓸 요약 필드만 추출하고, 원문 전체는
 * raw 로 함께 보존한다 (jsonb 저장 — 추출 로직 버그 시 원문에서 재생성, raw 토픽과 같은 원칙).
 *
 * 분석 요약(severity/rootCause/confidence/evidence/suggestedActions)이 비거나 null 일 수 있는
 * 이유: partial 보고서는 분석 노드 실패로 analysis 자체가 없다 (DAY 13 부분 보고서 구조).
 */
data class IncidentReport(
    val incidentId: String,
    val scenario: String,
    val alertName: String?,
    val status: String,
    val severity: String?,
    val rootCause: String?,
    val confidence: Double?,
    val evidence: List<String>,
    val suggestedActions: List<String>,
    val completedAt: Instant?,
    val raw: String,
    // 승인·실행·회복 요약 (DAY 24, ADR-0005) — 승인 왕복이 없던 보고서는 null·빈 목록 유지
    val approvalStatus: String? = null,
    val approvalDecidedBy: String? = null,
    val executions: List<ActionExecution> = emptyList(),
    val executedAt: Instant? = null,
    val recoveryStatus: String? = null,
    val recoveryDetail: String? = null,
) {

    companion object {
        private val mapper = JsonMapper.builder().build()

        /**
         * 파싱 불가·필수 필드 누락은 null — 호출 측이 경고 후 건너뛴다 (poison pill 이
         * 오프셋 커밋을 막으면 안 됨, agent-service 컨슈머와 같은 규약).
         */
        fun parse(payload: String): IncidentReport? {
            val root = runCatching { mapper.readTree(payload) }.getOrNull() ?: return null
            val incidentId = root.path("incident_id").stringOrNull() ?: return null
            val scenario = root.path("scenario").stringOrNull() ?: return null
            val status = root.path("status").stringOrNull() ?: return null
            val analysis = root.path("analysis")
            val approval = root.path("approval")
            val recovery = root.path("recovery")
            return IncidentReport(
                incidentId = incidentId,
                scenario = scenario,
                alertName = root.path("alert_name").stringOrNull(),
                status = status,
                severity = analysis.path("severity").stringOrNull(),
                rootCause = analysis.path("root_cause_hypothesis").stringOrNull(),
                confidence = analysis.path("confidence").let { if (it.isNumber) it.doubleValue() else null },
                evidence = analysis.path("evidence").stringList(),
                suggestedActions = analysis.path("suggested_actions").stringList(),
                // 발행 측 완료 시각 (Python isoformat — +00:00 오프셋이라 Instant.parse 불가,
                // OffsetDateTime 로 읽어 Instant 로 정규화 — 시점 표현은 Instant 로 통일)
                completedAt = root.path("completed_at").stringOrNull()
                    ?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() },
                raw = payload,
                approvalStatus = approval.path("status").stringOrNull(),
                approvalDecidedBy = approval.path("decided_by").stringOrNull(),
                executions = approval.path("executions").executionList(),
                executedAt = approval.path("executed_at").stringOrNull()
                    ?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() },
                recoveryStatus = recovery.path("status").stringOrNull(),
                recoveryDetail = recovery.path("detail").stringOrNull(),
            )
        }

        private fun JsonNode.executionList(): List<ActionExecution> =
            if (isArray) {
                mapNotNull { item ->
                    item.path("action").stringOrNull()?.let { action ->
                        ActionExecution(
                            action = action,
                            ok = item.path("ok").let { it.isBoolean && it.booleanValue() },
                            detail = item.path("detail").stringOrNull() ?: "",
                            manual = item.path("manual").let { it.isBoolean && it.booleanValue() },
                        )
                    }
                }
            } else {
                emptyList()
            }

        private fun JsonNode.stringOrNull(): String? = if (isString) stringValue() else null

        private fun JsonNode.stringList(): List<String> =
            if (isArray) mapNotNull { it.stringOrNull() } else emptyList()
    }
}
