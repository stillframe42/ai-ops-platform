package stillframe42.controlplane.incident.event

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 단위 테스트 경계 — 와이어 규약(snake_case 필드·순서·enum wire 값)만 검증. */
class IncidentEventTest {

    private fun event(status: IncidentStatus = IncidentStatus.FIRING) = IncidentEvent(
        incidentId = "inc-error-rate-surge-20260725093000-d38f7c",
        fingerprint = "d38f7c69cf7e2d2b",
        scenario = "error-rate-surge",
        alertName = "TargetAppHighErrorRate",
        severity = "critical",
        summary = "5xx 에러율 10% 초과",
        status = status,
        startsAt = "2026-07-25T09:28:00Z",
        occurredAt = Instant.parse("2026-07-25T09:30:00Z"),
        mergeCount = 0,
    )

    @Test
    fun `toWire 는 snake_case 필드를 규약 순서로 낸다 - Python 컨슈머 계약`() {
        val wire = event().toWire()

        assertEquals(
            listOf(
                "incident_id", "fingerprint", "scenario", "alert_name", "severity",
                "summary", "status", "starts_at", "occurred_at", "merge_count",
            ),
            wire.keys.toList(),
        )
        assertEquals("inc-error-rate-surge-20260725093000-d38f7c", wire["incident_id"])
        assertEquals("2026-07-25T09:30:00Z", wire["occurred_at"])
        assertEquals(0, wire["merge_count"])
    }

    @Test
    fun `status 는 enum 이름이 아니라 wire 값으로 나간다`() {
        assertEquals("firing", event(IncidentStatus.FIRING).toWire()["status"])
        assertEquals("resolved", event(IncidentStatus.RESOLVED).toWire()["status"])
    }

    @Test
    fun `fromWire 는 Alertmanager status 문자열을 enum 으로 - 미지 값은 null`() {
        assertEquals(IncidentStatus.FIRING, IncidentStatus.fromWire("firing"))
        assertEquals(IncidentStatus.RESOLVED, IncidentStatus.fromWire("resolved"))
        assertNull(IncidentStatus.fromWire("acked"))
        assertNull(IncidentStatus.fromWire(null))
    }
}
