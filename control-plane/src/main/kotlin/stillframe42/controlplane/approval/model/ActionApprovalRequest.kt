package stillframe42.controlplane.approval.model

import java.time.Instant
import java.time.OffsetDateTime
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * ops.actions.pending 수신 페이로드의 소비 측 표현 (DAY 22) — 발행 측은 agent-service
 * approval.build_approval_request. 승인 목록·Slack 카드에 쓸 요약 필드만 추출하고 원문
 * 전체는 raw 로 보존한다 (action_payload jsonb — 추출 버그 시 재생성 근거, report 원칙).
 *
 * actionType 은 대표 조치: 페이로드 actions 배열에서 NOTIFY_ONLY 를 제외한 첫 항목
 * (발행 측이 실행 조치 없는 계획은 승인 생략하므로 정상 페이로드엔 항상 존재).
 */
data class ActionApprovalRequest(
    val incidentId: String,
    val actionType: String,
    val riskLevel: String?,
    val confidence: Double?,
    val requestedAt: Instant,
    val raw: String,
    // 아래는 Slack 승인 카드 표시용 (DAY 23, ADR-0006 카드 내용 스펙) — 카드가 없으면 "-" 강등
    val scenario: String? = null,
    val alertName: String? = null,
    val rootCauseHypothesis: String? = null,
    val actions: List<String> = emptyList(),
    val rationale: String? = null,
    val expectedEffect: String? = null,
    val risk: String? = null,
) {

    companion object {
        private val mapper = JsonMapper.builder().build()

        /** 파싱 불가·필수 필드 누락은 null — 호출 측이 경고 후 건너뛴다 (IncidentReport 와 같은 규약). */
        fun parse(payload: String): ActionApprovalRequest? {
            val root = runCatching { mapper.readTree(payload) }.getOrNull() ?: return null
            val incidentId = root.path("incident_id").stringOrNull() ?: return null
            val actions = root.path("actions").stringList()
            val actionType = actions.firstOrNull { it != "NOTIFY_ONLY" } ?: actions.firstOrNull()
                ?: return null
            return ActionApprovalRequest(
                incidentId = incidentId,
                actionType = actionType,
                riskLevel = root.path("severity").stringOrNull(),
                confidence = root.path("confidence").let { if (it.isNumber) it.doubleValue() else null },
                // Python isoformat(+00:00) → Instant 정규화. 누락 시 수신 시각 폴백 — 컬럼 NOT NULL
                requestedAt = root.path("requested_at").stringOrNull()
                    ?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
                    ?: Instant.now(),
                raw = payload,
                scenario = root.path("scenario").stringOrNull(),
                alertName = root.path("alert_name").stringOrNull(),
                rootCauseHypothesis = root.path("root_cause_hypothesis").stringOrNull(),
                actions = actions,
                rationale = root.path("rationale").stringOrNull(),
                expectedEffect = root.path("expected_effect").stringOrNull(),
                risk = root.path("risk").stringOrNull(),
            )
        }

        private fun JsonNode.stringOrNull(): String? = if (isString) stringValue() else null

        private fun JsonNode.stringList(): List<String> =
            if (isArray) mapNotNull { it.stringOrNull() } else emptyList()
    }
}
