package stillframe42.controlplane.incident.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import stillframe42.controlplane.incident.model.IncidentReport
import stillframe42.controlplane.incident.model.IncidentReportDetail
import stillframe42.controlplane.incident.model.IncidentReportSummary
import stillframe42.controlplane.incident.notify.Notifier
import stillframe42.controlplane.incident.repository.IncidentReportRepository

/**
 * 결과 수신 처리 (DAY 19) — 파싱 → 멱등 저장 → 신규일 때만 알림.
 *
 * 트랜잭션 경계는 이 서비스가 소유한다 — 저장소는 단일 호출의 정합성만 보장할 수 있고,
 * 유스케이스가 여러 저장소 호출로 커지면 (예: 4주차 보고서+조치 동시 기록) 묶음 정합성은
 * 서비스 단위여야 한다. 조회 경로도 같은 이유로 컨트롤러가 아니라 여기를 거친다.
 *
 * 알림을 신규 저장에만 묶는 이유: at-least-once 라 같은 결과가 재발행·재전달될 수 있다
 * (agent-service 의 done 재발행 경로 실측, DAY 18) — 재수신은 갱신만 하고 알림은 내지 않는다.
 * 저장 예외는 그대로 전파 — 리스너 컨테이너의 재시도 경로가 처리 (AnalysisResultConsumer 주석).
 */
@Service
class IncidentReportService(
    private val repository: IncidentReportRepository,
    private val notifier: Notifier,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * 알림(notifier)은 예외를 던지지 않는 계약(Notifier KDoc)이라 트랜잭션을 되돌리는 일이
     * 없다 — 롤백은 저장 예외만 유발한다. Slack HTTP 가 트랜잭션 안에 드는 비용은
     * 커밋 후 발송(이벤트 분리)으로 없앨 수 있으나 현 트래픽에선 과함 — 4주차 재검토.
     */
    @Transactional
    fun ingest(payload: String) {
        val report = IncidentReport.parse(payload)
        if (report == null) {
            logger.warn("결과 페이로드 파싱 실패 — 건너뜀 (본문 {}자)", payload.length)
            return
        }
        val isNew = repository.upsert(report)
        if (isNew) {
            logger.info("인시던트 보고서 저장 — {} (status={})", report.incidentId, report.status)
            notifier.notify(report)
        } else {
            logger.info("결과 재수신 — {} 갱신만 수행 (알림 생략)", report.incidentId)
        }
    }

    fun findRecent(limit: Int): List<IncidentReportSummary> = repository.findRecent(limit)

    fun findById(incidentId: String): IncidentReportDetail? = repository.findById(incidentId)
}
