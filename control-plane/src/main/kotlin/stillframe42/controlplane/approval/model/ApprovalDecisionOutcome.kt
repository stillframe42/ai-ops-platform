package stillframe42.controlplane.approval.model

import java.time.Instant

/**
 * 승인 결정 처리 결과 — 서비스가 반환하고 입력 채널(API·Slack)이 각자 표현으로 매핑한다
 * (API: 404/409, Slack: 스레드 회신 — ADR-0006 "승인 처리는 한 곳, 채널은 입력만").
 */
sealed interface ApprovalDecisionOutcome {

    /** 승인 요청 행이 없다 — 모르는 인시던트이거나 아직 pending 이 도착하지 않았다 */
    data object NotFound : ApprovalDecisionOutcome

    /** 활성 pending 이 없고 종결 행만 있다 — 중복 결정 또는 만료 후 결정 */
    data class AlreadyDecided(val status: String) : ApprovalDecisionOutcome

    data class Decided(
        val incidentId: String,
        val status: String,
        val decidedBy: String,
        val decidedAt: Instant,
    ) : ApprovalDecisionOutcome
}
