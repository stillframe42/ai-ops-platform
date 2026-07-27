package stillframe42.controlplane.incident.controller

import java.time.Instant
import kotlin.test.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import stillframe42.controlplane.incident.model.IncidentReport
import stillframe42.controlplane.incident.model.IncidentReportDetail
import stillframe42.controlplane.incident.model.IncidentReportSummary
import stillframe42.controlplane.incident.repository.IncidentReportRepository
import stillframe42.controlplane.incident.service.IncidentReportService

/**
 * 단위 테스트 경계 — standalone MockMvc + fake 저장소 (DB 무의존, 실 서비스 경유 —
 * 컨트롤러는 서비스를 거치는 계층 구조라 테스트 조립도 같은 모양).
 * API 와이어(snake_case)와 404 규약만 검증한다.
 */
class IncidentQueryControllerTest {

    private val summary = IncidentReportSummary(
        incidentId = "inc-error-rate-surge-20260727031500-a1b2c3",
        scenario = "error-rate-surge",
        alertName = "TargetAppHighErrorRate",
        status = "completed",
        severity = "P2",
        rootCause = "배포 직후 신규 코드 결함으로 5xx 급증",
        confidence = 0.85,
        completedAt = Instant.parse("2026-07-27T03:20:11Z"),
        createdAt = Instant.parse("2026-07-27T03:20:12Z"),
        updatedAt = Instant.parse("2026-07-27T03:20:12Z"),
    )

    private class FakeRepository(
        private val summaries: List<IncidentReportSummary>,
        private val detail: IncidentReportDetail?,
    ) : IncidentReportRepository {
        override fun upsert(report: IncidentReport): Boolean = true
        override fun findRecent(limit: Int): List<IncidentReportSummary> = summaries.take(limit)
        override fun findById(incidentId: String): IncidentReportDetail? =
            detail?.takeIf { it.summary.incidentId == incidentId }
    }

    private fun mvc(repository: IncidentReportRepository) =
        MockMvcBuilders.standaloneSetup(
            IncidentQueryController(IncidentReportService(repository) { }),
        ).build()

    @Test
    fun `목록은 snake_case 요약 필드를 반환한다`() {
        val mvc = mvc(FakeRepository(listOf(summary), detail = null))

        mvc.perform(get("/api/incidents"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].incident_id").value(summary.incidentId))
            .andExpect(jsonPath("$[0].root_cause").value(summary.rootCause))
            .andExpect(jsonPath("$[0].confidence").value(0.85))
    }

    @Test
    fun `단건 조회는 보고서 원문을 JSON 객체로 포함한다`() {
        val detail = IncidentReportDetail(summary, """{"status": "completed", "supervisor_visits": 4}""")
        val mvc = mvc(FakeRepository(emptyList(), detail))

        mvc.perform(get("/api/incidents/${summary.incidentId}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.incident_id").value(summary.incidentId))
            // 문자열 재파싱 없이 바로 객체 — report 가 이중 인코딩되지 않았다는 증거
            .andExpect(jsonPath("$.report.supervisor_visits").value(4))
    }

    @Test
    fun `모르는 인시던트는 404`() {
        val mvc = mvc(FakeRepository(emptyList(), detail = null))

        mvc.perform(get("/api/incidents/inc-unknown")).andExpect(status().isNotFound)
    }
}
