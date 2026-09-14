package stillframe42.controlplane.incident.service

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import stillframe42.controlplane.incident.model.IncidentReport

/** 이벤트 → IncidentReportNotifier 인계 규약만 검증 — AFTER_COMMIT 시점은 스프링 컨텍스트 몫 (E2E 확인). */
class IncidentReportStoredListenerTest {

    @Test
    fun `저장 이벤트를 받으면 보고서를 알림으로 넘긴다`() {
        val notified = mutableListOf<IncidentReport>()
        val report = IncidentReport(
            incidentId = "inc-memory-pressure-20260727050000-d4e5f6",
            scenario = "memory-pressure",
            alertName = null,
            status = "completed",
            severity = "P2",
            rootCause = null,
            confidence = null,
            evidence = emptyList(),
            suggestedActions = emptyList(),
            completedAt = Instant.parse("2026-07-27T05:00:00Z"),
            raw = "{}",
        )

        IncidentReportStoredListener { notified += it }.onStored(IncidentReportStored(report))

        assertEquals(listOf(report), notified)
    }
}
