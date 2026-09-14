package stillframe42.controlplane.evaluation.notify

import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import stillframe42.controlplane.evaluation.service.LowQualityEvaluationStored

/**
 * 저품질 신규 저장 → Slack 검토 요청 연결 — AFTER_COMMIT(저장 롤백 시 요청도 없음) + @Async(Slack 왕복을 Kafka 컨슈머
 * 스레드에서 분리, ApprovalRequestStoredListener 와 같은 근거).
 */
@Component
class LowQualityEvaluationStoredListener(private val reviewRequestNotifier: ReviewRequestNotifier) {

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onStored(event: LowQualityEvaluationStored) = reviewRequestNotifier.requestReview(event.id, event.evaluation)
}
