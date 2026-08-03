package stillframe42.controlplane.approval.notify

import com.slack.api.Slack
import com.slack.api.methods.MethodsClient
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import stillframe42.controlplane.approval.model.ActionApprovalRequest
import stillframe42.controlplane.approval.model.SlackMessageRef

/**
 * ApprovalMessenger 의 Slack App API 구현 (DAY 23, ADR-0006) — 기존 알림(Incoming Webhook,
 * SlackNotifier)과 발신 경로가 다르다: 버튼·chat.update·스레드는 Bot Token 의 Web API 만 가능.
 * 토큰·채널 미설정이면 조용한 비활성 (Langfuse 키-게이트 관례), 전송 실패는 로그만 (계약).
 */
@Component
class SlackApprovalMessenger(
    @Value("\${ops.slack.bot-token}") private val botToken: String,
    @Value("\${ops.slack.approval-channel}") private val channel: String,
) : ApprovalMessenger {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val active = botToken.isNotBlank() && channel.isNotBlank()

    private val methods: MethodsClient by lazy { Slack.getInstance().methods(botToken) }

    init {
        // 기동 시점 1회 진단 — "왜 승인 카드가 안 오지" 를 로그로 확인 가능하게 (토큰 값 미출력)
        logger.info(
            "Slack 승인 카드 {}",
            if (active) "활성 (channel=$channel)" else "비활성 — SLACK_BOT_TOKEN/SLACK_APPROVAL_CHANNEL 미설정",
        )
    }

    override fun sendApprovalRequest(request: ActionApprovalRequest): SlackMessageRef? {
        if (!active) {
            logger.info("Slack 승인 카드 비활성 — {} 발송 생략", request.incidentId)
            return null
        }
        return runCatching {
            val response = methods.chatPostMessage {
                it.channel(channel)
                    .text(ApprovalMessageFactory.fallbackText(request))
                    .blocks(ApprovalMessageFactory.requestBlocks(request))
            }
            if (response.isOk) {
                logger.info("승인 카드 발송 — {} (ts={})", request.incidentId, response.ts)
                SlackMessageRef(response.channel, response.ts)
            } else {
                logger.warn("승인 카드 발송 거절 — {}: {}", request.incidentId, response.error)
                null
            }
        }.onFailure {
            logger.warn("승인 카드 발송 실패 — {}: {}", request.incidentId, it.message)
        }.getOrNull()
    }

    override fun closeApprovalRequest(
        message: SlackMessageRef,
        request: ActionApprovalRequest,
        status: String,
        decidedBy: String,
        decidedAt: Instant,
    ) {
        if (!active) return
        runCatching {
            val response = methods.chatUpdate {
                it.channel(message.channel)
                    .ts(message.messageTs)
                    .text(ApprovalMessageFactory.fallbackText(request))
                    .blocks(ApprovalMessageFactory.decidedBlocks(request, status, decidedBy, decidedAt))
            }
            if (!response.isOk) {
                logger.warn("승인 카드 마감 거절 — {}: {}", request.incidentId, response.error)
            }
        }.onFailure {
            logger.warn("승인 카드 마감 실패 — {}: {}", request.incidentId, it.message)
        }
    }

    override fun postThreadReply(message: SlackMessageRef, text: String) {
        if (!active) return
        runCatching {
            val response = methods.chatPostMessage {
                it.channel(message.channel).threadTs(message.messageTs).text(text)
            }
            if (!response.isOk) {
                logger.warn("승인 스레드 회신 거절 — ts={}: {}", message.messageTs, response.error)
            }
        }.onFailure {
            logger.warn("승인 스레드 회신 실패 — ts={}: {}", message.messageTs, it.message)
        }
    }
}
