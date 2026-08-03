package stillframe42.controlplane.approval.notify

import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import stillframe42.controlplane.approval.service.ActionApprovalService
import stillframe42.controlplane.approval.service.ApprovalRequestStored

/**
 * 신규 pending 저장 → 승인 카드 발송 연결 (DAY 23) — AFTER_COMMIT + @Async:
 * - 저장이 롤백되면 카드도 나가지 않는다 (IncidentReportStoredListener 와 같은 근거)
 * - @Async 인 이유 둘: ① Slack HTTP 왕복을 Kafka 컨슈머 스레드에서 분리 (3주차 조건부
 *   이월의 승격 조건 충족 — 승인 요청 발송이 동기 소비 경로에 있었다) ② 좌표 기록이
 *   새 트랜잭션을 열어야 하는데, AFTER_COMMIT 동기 실행 안에서는 완료된 트랜잭션에
 *   합류해 쓰기가 커밋되지 않는다 — 새 스레드에는 트랜잭션 문맥이 없어 문제가 사라진다
 */
@Component
class ApprovalRequestStoredListener(
    private val messenger: ApprovalMessenger,
    private val service: ActionApprovalService,
) {

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onStored(event: ApprovalRequestStored) {
        val message = messenger.sendApprovalRequest(event.request) ?: return
        service.recordSlackMessage(event.request.incidentId, message)
    }
}
