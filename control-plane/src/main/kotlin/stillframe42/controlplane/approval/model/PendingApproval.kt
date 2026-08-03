package stillframe42.controlplane.approval.model

import java.time.Instant

/**
 * 타임아웃 스캔용 대기 행 투영 (DAY 23) — 스케줄러가 재알림/만료 판단에 쓰는 필드만.
 * slackMessage 는 카드 미발송(토큰 미설정·발송 실패) 시 null — 재알림 회신만 생략되고
 * 만료 전이는 Slack 과 무관하게 진행된다 (expired 는 도메인 규칙, ADR-0006).
 */
data class PendingApproval(
    val incidentId: String,
    val requestedAt: Instant,
    val remindedAt: Instant?,
    val slackMessage: SlackMessageRef?,
)
