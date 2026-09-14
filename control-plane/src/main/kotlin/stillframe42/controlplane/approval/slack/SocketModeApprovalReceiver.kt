package stillframe42.controlplane.approval.slack

import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.socket_mode.SocketModeApp
import com.slack.api.socket_mode.SocketModeClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import stillframe42.controlplane.approval.notify.ApprovalMessageFactory

/**
 * Socket Mode 승인 버튼 수신기 (DAY 23, ADR-0006) — WebSocket outbound 연결이라 공개 URL
 * 불요 (로컬 compose 스택 제약의 해소안). 토큰 2종 중 하나라도 없으면 비활성 기동 —
 * Slack 없이도 스택 전체가 정상 동작해야 한다 (승인 API 경로는 항상 유효).
 *
 * SmartLifecycle 인 이유: 컨텍스트 준비 완료 후 연결을 열고, 종료 시 WebSocket 을 정리한다
 * (재연결은 Bolt SocketModeApp 이 자체 처리). 배선만 담당 — 결정 매핑은 ApprovalButtonHandler.
 */
@Component
class SocketModeApprovalReceiver(
    private val approvalButtonHandler: ApprovalButtonHandler,
    @Value("\${ops.slack.bot-token}") private val botToken: String,
    @Value("\${ops.slack.app-token}") private val appToken: String,
) : SmartLifecycle {

    private val logger = LoggerFactory.getLogger(javaClass)

    private var socketModeApp: SocketModeApp? = null

    @Volatile
    private var running = false

    override fun start() {
        running = true
        if (botToken.isBlank() || appToken.isBlank()) {
            logger.info("Socket Mode 비활성 — SLACK_BOT_TOKEN/SLACK_APP_TOKEN 미설정 (승인은 API 경로만)")
            return
        }
        runCatching {
            val app = App(AppConfig.builder().singleTeamBotToken(botToken).build())
            registerBlockAction(app, ApprovalMessageFactory.ACTION_APPROVE)
            registerBlockAction(app, ApprovalMessageFactory.ACTION_REJECT)
            socketModeApp = SocketModeApp(appToken, SocketModeClient.Backend.JavaWebSocket, app)
                .also { it.startAsync() }
            logger.info("Socket Mode 연결 시작 — 승인 버튼 수신 대기")
        }.onFailure {
            // 수신 불가여도 앱은 살아야 한다 — 승인 API 경로가 항상 남아 있다 (IncidentReportNotifier 계약과 같은 태도)
            logger.warn("Socket Mode 시작 실패 — 승인은 API 경로만 가능: {}", it.message)
        }
    }

    private fun registerBlockAction(app: App, actionId: String) {
        app.blockAction(actionId) { req, ctx ->
            val action = req.payload.actions.firstOrNull()
            val incidentId = action?.value
            val slackUserId = req.payload.user?.id
            if (incidentId != null && slackUserId != null) {
                approvalButtonHandler.handle(actionId, incidentId, slackUserId)?.let { ctx.respond(it) }
            }
            ctx.ack()
        }
    }

    override fun stop() {
        running = false
        runCatching { socketModeApp?.stop() }
            .onFailure { logger.warn("Socket Mode 종료 중 오류 — {}", it.message) }
        socketModeApp = null
    }

    override fun isRunning(): Boolean = running
}
