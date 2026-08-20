package stillframe42.controlplane.incident.event

import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import stillframe42.controlplane.alert.event.OpsTopics
import stillframe42.controlplane.incident.service.IncidentReportService

/**
 * ops.analysis.results 컨슈머 (DAY 19) — 수신 페이로드를 서비스로 넘기는 소비 측 Kafka 경계.
 * 오프셋 커밋은 리스너 정상 반환 후(at-least-once) — 멱등·건너뜀 규약은 IncidentReportService 소유.
 * 예외 전파 시 기본 에러 핸들러가 재시도 후 건너뛴다 (장기 DB 다운 = 유실 경로, Phase 7 소재).
 */
@Component
class AnalysisResultConsumer(private val incidentReportService: IncidentReportService) {

    @KafkaListener(topics = [OpsTopics.ANALYSIS_RESULTS], groupId = "control-plane")
    fun onResult(payload: String) = incidentReportService.ingest(payload)
}
