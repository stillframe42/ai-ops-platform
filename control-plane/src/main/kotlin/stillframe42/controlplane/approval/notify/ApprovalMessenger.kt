package stillframe42.controlplane.approval.notify

import java.time.Instant
import stillframe42.controlplane.approval.model.ActionApprovalRequest
import stillframe42.controlplane.approval.model.SlackMessageRef

/**
 * 승인 카드 발송 경계 (DAY 23) — 구현(Slack App API)과 테스트 fake 의 주입 지점
 * (IncidentReportNotifier 와 같은 목적, 다만 발송 결과 좌표가 필요해 fun interface 가 아닌 3-메서드).
 * 계약: 어떤 메서드도 예외를 던지지 않는다 — 발송 실패가 저장·전이를 되돌리면 안 된다.
 */
interface ApprovalMessenger {

    /** 승인 카드 발송 — 비활성(토큰 미설정)·실패 시 null (호출 측은 좌표 기록만 생략) */
    fun sendApprovalRequest(request: ActionApprovalRequest): SlackMessageRef?

    /** 카드 마감 — 버튼 제거 + 결정 결과 표시 (chat.update) */
    fun closeApprovalRequest(
        message: SlackMessageRef,
        request: ActionApprovalRequest,
        status: String,
        decidedBy: String,
        decidedAt: Instant,
    )

    /** 카드 스레드 회신 — 결정 결과·재알림이 원 카드의 대화 맥락에 남는다 */
    fun postThreadReply(message: SlackMessageRef, text: String)
}
