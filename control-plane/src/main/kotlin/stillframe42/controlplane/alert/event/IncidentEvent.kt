package stillframe42.controlplane.alert.event

import java.time.Instant

/**
 * 인시던트 상태 — 코드 안에서는 enum, 와이어에서는 소문자 문자열.
 * 값 집합은 Alertmanager 의 alert status 어휘(firing/resolved)를 그대로 따른다 —
 * 우리 이벤트의 status 가 그 파싱 결과에서 파생되기 때문 (수신 파싱도 fromWire 로 일원화).
 */
enum class IncidentStatus(val wire: String) {
    FIRING("firing"),
    RESOLVED("resolved"),
    ;

    companion object {
        /** 미지 값은 null — 호출 측이 경고 후 건너뛴다 (프로토콜 확장에 대한 관용). */
        fun fromWire(value: String?): IncidentStatus? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * ops.incidents 발행 페이로드 (DAY 17) — Python 컨슈머(DAY 19)와의 계약을 타입으로 고정한다.
 * 필드 추가·오타·누락이 컴파일 단계에서 걸리고, 와이어 형태(snake_case·순서)는 toWire 한 곳이 소유.
 */
data class IncidentEvent(
    val incidentId: String,
    val fingerprint: String,
    val scenario: String,
    val alertName: String,
    val severity: String?,
    val summary: String?,
    val status: IncidentStatus,
    val startsAt: String?,
    val occurredAt: Instant,
    val mergeCount: Int,
) {
    /** 직렬화용 표현 — key 순서가 곧 발행 JSON 의 필드 순서다 (IncidentEventTest 가 고정). */
    fun toWire(): Map<String, Any?> = linkedMapOf(
        "incident_id" to incidentId,
        "fingerprint" to fingerprint,
        "scenario" to scenario,
        "alert_name" to alertName,
        "severity" to severity,
        "summary" to summary,
        "status" to status.wire,
        "starts_at" to startsAt,
        "occurred_at" to occurredAt.toString(),
        "merge_count" to mergeCount,
    )
}
