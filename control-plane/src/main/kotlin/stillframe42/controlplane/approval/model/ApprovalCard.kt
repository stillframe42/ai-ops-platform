package stillframe42.controlplane.approval.model

/**
 * Slack 카드 마감(chat.update)용 재구성 정보 (DAY 23) — 결정 시점에는 원 카드 내용이
 * 메모리에 없으므로 저장된 action_payload 원문에서 요청서를 재파싱한다 (jsonb 보존 원칙의
 * 실사용 지점). slackMessage 가 null 이면 마감할 카드가 없다 (발송 생략 건).
 */
data class ApprovalCard(
    val request: ActionApprovalRequest,
    val slackMessage: SlackMessageRef?,
)
