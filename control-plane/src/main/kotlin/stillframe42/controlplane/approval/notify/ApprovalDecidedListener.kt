package stillframe42.controlplane.approval.notify

import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import stillframe42.controlplane.approval.repository.ActionApprovalRepository
import stillframe42.controlplane.approval.service.ApprovalDecided

/**
 * 승인 결정 → Slack 카드 마감 연결 (DAY 23) — 버튼·API·타임아웃 어느 경로의 결정이든
 * 여기 한 곳이 카드 갱신(버튼 제거)과 스레드 회신을 담당한다 (ADR-0006 "처리는 한 곳" 의
 * 출력판). 카드 좌표가 없으면(발송 생략 건) 조용히 마친다 — Slack 은 부가 채널이다.
 */
@Component
class ApprovalDecidedListener(
    private val repository: ActionApprovalRepository,
    private val messenger: ApprovalMessenger,
) {

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onDecided(event: ApprovalDecided) {
        val card = repository.findLatestCard(event.incidentId) ?: return
        val message = card.slackMessage ?: return
        messenger.closeApprovalRequest(message, card.request, event.status, event.decidedBy, event.decidedAt)
        messenger.postThreadReply(
            message,
            ApprovalMessageFactory.resultThreadText(event.status, event.decidedBy, event.decidedAt),
        )
    }
}
