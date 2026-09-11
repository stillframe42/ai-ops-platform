package stillframe42.controlplane.evaluation.event

import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import stillframe42.controlplane.alert.event.OpsTopics
import stillframe42.controlplane.evaluation.service.IncidentEvaluationService

/**
 * ops.evaluation.results 컨슈머 — 페이로드를 서비스로 넘기는 소비 측 Kafka 경계 (AnalysisResultConsumer 와 같은 골격).
 * 오프셋 커밋은 리스너 정상 반환 후(at-least-once) — 멱등·건너뜀 규약은 IncidentEvaluationService 소유.
 */
@Component
class EvaluationResultConsumer(private val incidentEvaluationService: IncidentEvaluationService) {

    @KafkaListener(topics = [OpsTopics.EVALUATION_RESULTS], groupId = "control-plane")
    fun onResult(payload: String) = incidentEvaluationService.ingest(payload)
}
