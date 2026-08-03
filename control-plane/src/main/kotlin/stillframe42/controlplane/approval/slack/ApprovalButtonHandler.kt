package stillframe42.controlplane.approval.slack

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import stillframe42.controlplane.approval.model.ApprovalDecisionOutcome
import stillframe42.controlplane.approval.model.ApprovalStatus
import stillframe42.controlplane.approval.notify.ApprovalMessageFactory
import stillframe42.controlplane.approval.service.ActionApprovalService
import stillframe42.controlplane.approval.service.DecisionPublishFailedException

/**
 * 승인 버튼 클릭 → 결정 매핑 (DAY 23) — Slack 은 입력 채널일 뿐, 결정은 승인 API 와 같은
 * ActionApprovalService.decide 한 곳으로 수렴한다 (ADR-0006). Bolt 배선(수신기)과 분리한
 * 이유: 이 매핑 규약은 순수 로직이라 fake 주입으로 단위 테스트한다.
 *
 * 반환 텍스트는 채널 즉답이 필요한 경우만 — 정상 결정의 회신은 ApprovalDecidedListener 가
 * 스레드로 담당하므로 여기서는 null (중복 회신 방지).
 */
@Component
class ApprovalButtonHandler(private val service: ActionApprovalService) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun handle(actionId: String, incidentId: String, slackUserId: String): String? {
        val status = when (actionId) {
            ApprovalMessageFactory.ACTION_APPROVE -> ApprovalStatus.APPROVED
            ApprovalMessageFactory.ACTION_REJECT -> ApprovalStatus.REJECTED
            else -> return null // 모르는 버튼 — 이 카드의 결정 입력이 아니다
        }
        return try {
            when (val outcome = service.decide(incidentId, status, slackUserId)) {
                is ApprovalDecisionOutcome.Decided -> null
                is ApprovalDecisionOutcome.AlreadyDecided ->
                    "이미 ${outcome.status} 로 종결된 요청입니다 — `$incidentId`"
                ApprovalDecisionOutcome.NotFound ->
                    "승인 요청을 찾을 수 없습니다 — `$incidentId`"
            }
        } catch (e: DecisionPublishFailedException) {
            // 전이는 롤백됨 (503 과 같은 경로) — 버튼 재클릭이 곧 재시도
            logger.warn("버튼 결정의 decisions 발행 접수 실패 — {}", incidentId)
            "재개 신호 발행 접수에 실패했습니다 — 잠시 후 버튼을 다시 눌러 주세요 (`$incidentId`)"
        }
    }
}
