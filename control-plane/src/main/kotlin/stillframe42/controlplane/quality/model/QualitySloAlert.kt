package stillframe42.controlplane.quality.model

import tools.jackson.databind.JsonNode

/**
 * Alertmanager 알림 중 `kind=quality` 라벨을 가진 것 — 인시던트가 아니라 품질 SLO 신호 (docs/quality-evaluation.md §2 임계).
 * 인시던트화하지 않는 이유: 대상이 target-app 장애가 아니라 분석 에이전트의 출력 품질이라 에이전트를 다시 돌릴 일이 없다.
 */
data class QualitySloAlert(
    val alertName: String,
    val status: String,
    val severity: String?,
    /** 알림 출처 환경 — Prometheus external_labels(compose / kind). 두 환경이 같은 Slack 채널을 쓴다 */
    val cluster: String?,
    val summary: String?,
    val description: String?,
    val startsAt: String?,
) {
    companion object {
        const val KIND_LABEL = "kind"
        const val KIND_QUALITY = "quality"

        fun isQuality(alert: JsonNode): Boolean = alert.path("labels").path(KIND_LABEL).let { it.isString && it.stringValue() == KIND_QUALITY }

        fun from(alert: JsonNode): QualitySloAlert? {
            val labels = alert.path("labels")
            val annotations = alert.path("annotations")
            return QualitySloAlert(
                alertName = labels.path("alertname").stringOrNull() ?: return null,
                status = alert.path("status").stringOrNull() ?: return null,
                severity = labels.path("severity").stringOrNull(),
                cluster = labels.path("cluster").stringOrNull(),
                summary = annotations.path("summary").stringOrNull(),
                description = annotations.path("description").stringOrNull(),
                startsAt = alert.path("startsAt").stringOrNull(),
            )
        }

        private fun JsonNode.stringOrNull(): String? = if (isString) stringValue() else null
    }
}
