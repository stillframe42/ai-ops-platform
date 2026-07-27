package stillframe42.controlplane.incident.service

import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.context.ApplicationEventPublisher
import stillframe42.controlplane.incident.model.IncidentReport
import stillframe42.controlplane.incident.model.IncidentReportDetail
import stillframe42.controlplane.incident.model.IncidentReportSummary
import stillframe42.controlplane.incident.repository.IncidentReportRepository

/**
 * 단위 테스트 경계 — Kafka·DB 무의존, fake 주입. "언제 저장하고 언제 알림 이벤트를 내는가"
 * (멱등·알림 규약)만 검증한다. AFTER_COMMIT 시점 실행은 스프링 컨텍스트 몫 — E2E 에서 확인.
 */
class IncidentReportServiceTest {

    private class RecordingRepository(private val isNew: Boolean) : IncidentReportRepository {
        val upserted = mutableListOf<IncidentReport>()
        override fun upsert(report: IncidentReport): Boolean {
            upserted += report
            return isNew
        }
        override fun findRecent(limit: Int): List<IncidentReportSummary> = emptyList()
        override fun findById(incidentId: String): IncidentReportDetail? = null
    }

    private class RecordingEvents : ApplicationEventPublisher {
        val published = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            published += event
        }
    }

    private fun payload(id: String = "inc-latency-surge-20260727040000-c3d4e5") = """
        {"incident_id": "$id", "scenario": "latency-surge", "status": "completed"}
    """.trimIndent()

    @Test
    fun `신규 저장이면 알림 이벤트를 발행한다`() {
        val repository = RecordingRepository(isNew = true)
        val events = RecordingEvents()

        IncidentReportService(repository, events).ingest(payload())

        assertEquals(1, repository.upserted.size)
        val stored = events.published.single() as IncidentReportStored
        assertEquals(repository.upserted.single().incidentId, stored.report.incidentId)
    }

    @Test
    fun `재수신이면 갱신만 하고 이벤트는 내지 않는다 - at-least-once 재발행 짝`() {
        val repository = RecordingRepository(isNew = false)
        val events = RecordingEvents()

        IncidentReportService(repository, events).ingest(payload())

        assertEquals(1, repository.upserted.size)
        assertEquals(0, events.published.size)
    }

    @Test
    fun `파싱 불가 페이로드는 저장·이벤트 없이 건너뛴다 - poison pill 이 커밋을 막지 않는다`() {
        val repository = RecordingRepository(isNew = true)
        val events = RecordingEvents()

        IncidentReportService(repository, events).ingest("not-json{{{")

        assertEquals(0, repository.upserted.size)
        assertEquals(0, events.published.size)
    }
}
