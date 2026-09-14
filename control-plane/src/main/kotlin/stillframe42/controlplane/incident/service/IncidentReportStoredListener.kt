package stillframe42.controlplane.incident.service

import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import stillframe42.controlplane.incident.notify.IncidentReportNotifier

/**
 * 신규 저장 이벤트 → 알림 연결 (DAY 19) — AFTER_COMMIT 이므로:
 * - 트랜잭션이 커밋을 마치고 DB 커넥션을 반납한 뒤에 HTTP 발송이 시작된다 (자원 점유 분리)
 * - 저장이 롤백되면 알림도 나가지 않는다 (저장 안 된 보고서의 알림 선발송 불일치 차단)
 */
@Component
class IncidentReportStoredListener(private val notifier: IncidentReportNotifier) {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onStored(event: IncidentReportStored) = notifier.notify(event.report)
}
