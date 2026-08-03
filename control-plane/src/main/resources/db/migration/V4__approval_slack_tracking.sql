-- 승인 카드 Slack 좌표 + 재알림 추적 (DAY 23, ADR-0006 Socket Mode 승인 UX).
-- slack_channel/slack_message_ts: 카드 마감(chat.update 버튼 제거)·스레드 회신의 대상 좌표 —
--   DB 에 두는 이유는 재시작 내성 (인메모리 좌표는 control-plane 재시작 시 유실되어
--   타임아웃 재알림·만료 회신이 카드를 찾지 못한다). Slack 미발송(토큰 미설정) 카드는 null.
-- reminded_at: 30분 재알림 1회 규약의 표식 — null 이면 아직 재알림 전 (값은 재알림 시각).
ALTER TABLE action_approvals
    ADD COLUMN slack_channel    text,
    ADD COLUMN slack_message_ts text,
    ADD COLUMN reminded_at      timestamptz;
