package stillframe42.controlplane.approval.event

import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import stillframe42.controlplane.alert.event.OpsTopics
import stillframe42.controlplane.approval.service.ActionApprovalService

/**
 * ops.actions.pending 컨슈머 (DAY 22, ADR-0005) — agent-actionApprovalService 가 interrupt 대기에 들어가며
 * 발행한 승인 요청서를 action_approvals(pending) 로 영속화한다. 커밋은 리스너 정상 반환 후
 * (at-least-once) — 재전달 멱등은 저장소의 "활성 pending 1건" 규약이 흡수 (AnalysisResultConsumer 와
 * 같은 소비 규약, 저장 예외는 전파해 컨테이너 에러 핸들러의 재시도 경로로).
 */
@Component
class ActionsPendingConsumer(private val actionApprovalService: ActionApprovalService) {

    @KafkaListener(topics = [OpsTopics.ACTIONS_PENDING], groupId = "control-plane")
    fun onPending(payload: String) = actionApprovalService.ingest(payload)
}
