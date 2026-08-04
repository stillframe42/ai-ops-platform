package stillframe42.controlplane.approval.notify

import com.slack.api.model.block.Blocks.actions
import com.slack.api.model.block.Blocks.asBlocks
import com.slack.api.model.block.Blocks.context
import com.slack.api.model.block.Blocks.section
import com.slack.api.model.block.LayoutBlock
import com.slack.api.model.block.composition.BlockCompositions.markdownText
import com.slack.api.model.block.composition.BlockCompositions.plainText
import com.slack.api.model.block.element.BlockElements.asElements
import com.slack.api.model.block.element.BlockElements.button
import java.time.Duration
import java.time.Instant
import stillframe42.controlplane.approval.model.ActionApprovalRequest
import stillframe42.controlplane.approval.model.ActionExecution
import stillframe42.controlplane.approval.model.ApprovalStatus

/**
 * 승인 카드 메시지 조립 (DAY 23, ADR-0006 카드 내용 스펙) — 순수 함수만: HTTP 무의존이라
 * 블록 구조를 그대로 단위 테스트한다 (SlackNotifier.buildMessage 와 같은 경계 전략).
 * 본문 요약은 DAY 19 알림 포맷의 확장 — 기존 알림 내용 + 조치안·사유 + [승인][거부] 버튼.
 */
object ApprovalMessageFactory {

    /** Socket Mode 수신 측(SocketModeApprovalReceiver)과 공유하는 버튼 식별자 */
    const val ACTION_APPROVE = "approval-approve"
    const val ACTION_REJECT = "approval-reject"
    const val BLOCK_DECISION = "approval-decision"

    /** 알림 미리보기·블록 미지원 클라이언트용 대체 텍스트 */
    fun fallbackText(request: ActionApprovalRequest): String =
        "[${request.riskLevel ?: "P?"}] ${request.scenario ?: "unknown"} 조치 승인 요청 — ${request.actionType}"

    /** 승인 요청 카드 — 요약 섹션 + [승인][거부] 버튼 (버튼 value = incident_id 가 왕복 키) */
    fun requestBlocks(request: ActionApprovalRequest): List<LayoutBlock> = asBlocks(
        section { it.text(markdownText(summaryMarkdown(request))) },
        actions {
            it.blockId(BLOCK_DECISION).elements(
                asElements(
                    button { b ->
                        b.actionId(ACTION_APPROVE).text(plainText("승인"))
                            .style("primary").value(request.incidentId)
                    },
                    button { b ->
                        b.actionId(ACTION_REJECT).text(plainText("거부"))
                            .style("danger").value(request.incidentId)
                    },
                ),
            )
        },
    )

    /** 결정 후 카드 — 같은 요약에 결과 줄을 붙이고 버튼은 제거 (chat.update 대상) */
    fun decidedBlocks(
        request: ActionApprovalRequest,
        status: String,
        decidedBy: String,
        decidedAt: Instant,
    ): List<LayoutBlock> = asBlocks(
        section { it.text(markdownText(summaryMarkdown(request))) },
        context { it.elements(listOf(markdownText(resultLine(status, decidedBy, decidedAt)))) },
    )

    /** 결정 결과 스레드 회신 — 카드 갱신과 별개로 스레드에 남겨 결정 이력이 대화로 보이게 */
    fun resultThreadText(status: String, decidedBy: String, decidedAt: Instant): String =
        resultLine(status, decidedBy, decidedAt)

    /** 조치 실행 결과 스레드 회신 (DAY 24) — 결정 회신과 별개 메시지: 실행은 결정보다 늦게 끝난다 */
    fun executionThreadText(executions: List<ActionExecution>): String {
        if (executions.isEmpty()) {
            return ":gear: 실행할 조치 없음 — 결정 통보만 진행"
        }
        return ":gear: *조치 실행 결과*\n" + executions.joinToString("\n") {
            // 수동 항목은 실행이 아니라 요청 — 운영자가 할 일이 남았음을 눈에 띄게 (2026-08-04 결정)
            val mark = if (it.manual) ":hand:" else if (it.ok) ":white_check_mark:" else ":x:"
            val label = if (it.manual) "${it.action} *수동 조치 필요*" else it.action
            "$mark $label — ${it.detail}"
        }
    }

    fun reminderText(remindAfter: Duration, expireAfter: Duration): String =
        ":hourglass_flowing_sand: 승인 대기 ${remindAfter.toMinutes()}분 경과 — " +
            "${expireAfter.toMinutes()}분까지 미결정이면 만료(조치 미실행) 처리됩니다"

    private fun resultLine(status: String, decidedBy: String, decidedAt: Instant): String =
        when (status) {
            ApprovalStatus.APPROVED -> ":white_check_mark: *승인* — $decidedBy ($decidedAt)"
            ApprovalStatus.REJECTED -> ":no_entry_sign: *거부* — $decidedBy ($decidedAt) · 조치 없이 종결"
            ApprovalStatus.EXPIRED -> ":hourglass: *만료* — 대기 시간 초과 ($decidedAt) · 조치 없이 종결"
            else -> "$status — $decidedBy ($decidedAt)"
        }

    private fun summaryMarkdown(request: ActionApprovalRequest): String {
        val lines = mutableListOf<String>()
        lines += ":vertical_traffic_light: *[${request.riskLevel ?: "P?"}] " +
            "${request.scenario ?: "unknown"} 조치 승인 요청*"
        lines += "• 인시던트: `${request.incidentId}`" + (request.alertName?.let { " ($it)" } ?: "")
        lines += "• 원인 가설: ${request.rootCauseHypothesis ?: "-"}"
        lines += "• confidence: ${request.confidence?.let { "%.2f".format(it) } ?: "-"}"
        lines += "• 조치안: ${request.actions.ifEmpty { listOf(request.actionType) }.joinToString(", ")}"
        request.rationale?.let { lines += "• 사유: $it" }
        request.expectedEffect?.let { lines += "• 기대 효과: $it" }
        request.risk?.let { lines += "• 리스크: $it" }
        return lines.joinToString("\n")
    }
}
