package stillframe42.controlplane.approval.execute

import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import stillframe42.controlplane.approval.model.ApprovalStatus
import stillframe42.controlplane.approval.notify.ApprovalMessageFactory
import stillframe42.controlplane.approval.notify.ApprovalMessenger
import stillframe42.controlplane.approval.repository.ActionApprovalRepository
import stillframe42.controlplane.approval.service.ActionApprovalService
import stillframe42.controlplane.approval.service.ApprovalDecided

/**
 * 승인 확정 → 조치 실행 → decisions 발행 연결 (DAY 24, ADR-0005 "실행 후 발행").
 * approved 전용 — rejected/expired 의 decisions 는 decide 트랜잭션 안에서 즉시 발행된다
 * (실행이 없는 결정은 발행 실패 롤백의 안전 규약을 유지 — 비대칭 배선의 근거).
 *
 * AFTER_COMMIT + @Async(전용 풀): 결정 커밋 전에 실행이 시작되면 안 되고(미확정 결정의
 * 조치 금지), 수 초~수십 초의 실행이 결정 입력 스레드(버튼·API·스케줄러)를 점유해서도
 * 안 된다. 실행이 실패해도 decisions 는 발행한다 — 대기 중인 그래프에 재개 신호를 보내는
 * 것이 우선이고, 성패는 페이로드 execution 항목이 전달한다.
 */
@Component
class ActionExecutionListener(
    private val repository: ActionApprovalRepository,
    private val executor: ActionExecutor,
    private val service: ActionApprovalService,
    private val messenger: ApprovalMessenger,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Async(ActionExecutionConfig.ACTION_EXECUTION_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onDecided(event: ApprovalDecided) {
        if (event.status != ApprovalStatus.APPROVED) return
        val card = repository.findLatestCard(event.incidentId)
        val actions = card?.request?.actions?.filter { it != NOTIFY_ONLY }.orEmpty()
        val executions = actions.map { executor.execute(it) }
        val executedAt = Instant.now()
        if (executions.isEmpty()) {
            logger.warn("실행할 조치 없음 — {} (조치안 원문 파싱 실패 또는 실행 조치 0건)", event.incidentId)
        } else {
            service.recordExecution(event.incidentId, executedAt, executions)
        }
        service.publishExecutedDecision(event, executions, executedAt)
        val message = card?.slackMessage ?: return
        messenger.postThreadReply(message, ApprovalMessageFactory.executionThreadText(executions))
    }

    companion object {
        /** 알림 전용 항목은 실행 대상이 아니다 — 발행 측 승인 생략 기준과 같은 값 */
        const val NOTIFY_ONLY = "NOTIFY_ONLY"
    }
}
