package stillframe42.controlplane.approval.service

import java.time.Instant

/**
 * 승인 결정 확정 이벤트 (DAY 23) — 어느 입력 경로(Slack 버튼·승인 API·타임아웃 만료)로
 * 결정돼도 decide 가 이 이벤트 하나를 발행하고, AFTER_COMMIT 리스너가 Slack 카드 마감
 * (버튼 제거)과 스레드 회신을 담당한다. 전이·decisions 발행이 롤백되면 회신도 나가지 않는다.
 */
data class ApprovalDecided(
    val incidentId: String,
    val status: String,
    val decidedBy: String,
    val decidedAt: Instant,
)
