package stillframe42.controlplane.incident.event

import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import stillframe42.controlplane.alert.event.OpsTopics
import stillframe42.controlplane.incident.service.IncidentReportService

/**
 * ops.analysis.results 컨슈머 (DAY 19) — 발행 측은 agent-service (DAY 18).
 * alert.event 가 발행 측 Kafka 경계이듯 여기는 소비 측 Kafka 경계다 (패키지 대칭).
 *
 * - groupId=control-plane: agent-service 그룹과 독립 오프셋 — 같은 토픽의 다중 독립 소비
 * - 커밋은 리스너 정상 반환 후 컨테이너가 배치 커밋 (spring-kafka 기본, enable.auto.commit=false)
 *   → at-least-once — 저장이 incident_id upsert 멱등인 것과 짝
 * - 파싱 불가(poison pill)는 서비스가 경고 후 정상 반환 — 커밋을 막지 않는다
 * - 저장 예외(DB 다운 등)는 전파 — 기본 에러 핸들러가 재시도(10회) 후 건너뜀.
 *   장기 DB 다운이면 건너뜀 = 유실 경로 — Phase 7 통합 테스트에서 backoff 조정과 함께 검증
 */
@Component
class AnalysisResultConsumer(private val service: IncidentReportService) {

    @KafkaListener(topics = [OpsTopics.ANALYSIS_RESULTS], groupId = "control-plane")
    fun onResult(payload: String) = service.ingest(payload)
}
