package stillframe42.controlplane.evaluation.service

import kotlin.test.Test
import kotlin.test.assertEquals
import stillframe42.controlplane.evaluation.model.IncidentEvaluation
import stillframe42.controlplane.evaluation.model.IncidentEvaluationDetail
import stillframe42.controlplane.evaluation.repository.IncidentEvaluationRepository

/** 단위 테스트 경계 — Kafka·DB 무의존, fake 저장소. 멱등 저장·건너뜀 규약만 검증한다 (IncidentReportServiceTest 와 같은 모양) */
class IncidentEvaluationServiceTest {

    private class RecordingRepository(private val isNew: Boolean) : IncidentEvaluationRepository {
        val upserted = mutableListOf<IncidentEvaluation>()
        override fun upsert(evaluation: IncidentEvaluation): Boolean {
            upserted += evaluation
            return isNew
        }
        override fun findByIncidentId(incidentId: String): List<IncidentEvaluationDetail> = emptyList()
    }

    private val payload = """
        {"incident_id": "inc-x", "scores": {"faithfulness": {"score": 1.0, "reason": ""}, "actionability": {"score": 1.0, "reason": ""},
         "severity_accuracy": {"score": 0.4, "reason": "과대"}}, "failure_mode": "D", "low_quality": true,
         "judge_model": "gpt-5.6-terra", "prompt_version": "v1", "evidence_available": false, "evaluated_at": "2026-09-11T02:00:00+00:00"}
    """.trimIndent()

    @Test
    fun `신규·재수신 모두 upsert 로 저장한다`() {
        val fresh = RecordingRepository(isNew = true)
        IncidentEvaluationService(fresh).ingest(payload)
        assertEquals(1, fresh.upserted.size)
        assertEquals("D", fresh.upserted.single().failureMode)

        val again = RecordingRepository(isNew = false)
        IncidentEvaluationService(again).ingest(payload)
        assertEquals(1, again.upserted.size)
    }

    @Test
    fun `파싱 불가 페이로드는 저장 없이 건너뛴다`() {
        val repository = RecordingRepository(isNew = true)
        IncidentEvaluationService(repository).ingest("{\"incident_id\": \"inc-x\"}")
        assertEquals(0, repository.upserted.size)
    }
}
