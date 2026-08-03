package stillframe42.controlplane.approval.model

/**
 * Slack 메시지 좌표 (DAY 23) — 카드 마감(chat.update)·스레드 회신(thread_ts)이 이 두 값을
 * 대상으로 한다. channel 은 설정값이 아닌 발송 응답의 채널 ID 를 보존한다 (채널명 설정이어도
 * 응답은 ID — 이후 API 호출의 안정 좌표).
 */
data class SlackMessageRef(
    val channel: String,
    val messageTs: String,
)
