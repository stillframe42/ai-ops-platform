package stillframe42.controlplane.approval.service

import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import stillframe42.controlplane.approval.model.ApprovalStatus
import stillframe42.controlplane.approval.model.PendingApproval
import stillframe42.controlplane.approval.notify.ApprovalMessageFactory
import stillframe42.controlplane.approval.notify.ApprovalMessenger
import stillframe42.controlplane.approval.repository.ActionApprovalRepository

/**
 * 승인 타임아웃 스캔 (DAY 23, ADR-0006) — remind-after 경과 시 재알림 1회, expire-after
 * 경과 시 expired 전이 = 조치 미실행 종결 (자동 승인 아님 — 오래된 관측 기반 조치의 실행이
 * 더 위험하다는 결정). 만료는 decide 경로 재사용이라 decisions 발행·agent 재개까지 동일.
 *
 * 값이 설정인 이유: 3경로 검증을 단축값으로 실측하기 위함 (30분 실대기 회피).
 * 만료 전이는 Slack 과 무관하게 진행 — 재알림·회신만 카드 좌표가 있을 때 나간다.
 */
@Component
class ApprovalTimeoutScheduler(
    private val actionApprovalService: ActionApprovalService,
    private val actionApprovalRepository: ActionApprovalRepository,
    private val approvalMessenger: ApprovalMessenger,
    @Value("\${ops.approval.remind-after}") private val remindAfter: Duration,
    @Value("\${ops.approval.expire-after}") private val expireAfter: Duration,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${ops.approval.sweep-interval}")
    fun sweep() = sweepAt(Instant.now())

    /** 기준 시각을 인자로 받는 이유: 테스트가 시계 대역 없이 경계를 실측하기 위해 */
    fun sweepAt(now: Instant) {
        val stale = actionApprovalRepository.findPendingRequestedBefore(now.minus(remindAfter))
        for (pending in stale) {
            // 한 건의 실패(발행 접수 등)가 나머지 스캔을 막지 않는다 — 다음 주기가 재시도
            runCatching { handle(pending, now) }
                .onFailure { logger.warn("타임아웃 처리 실패 — {}: {}", pending.incidentId, it.message) }
        }
    }

    private fun handle(pending: PendingApproval, now: Instant) {
        if (!pending.requestedAt.isAfter(now.minus(expireAfter))) {
            logger.info("승인 대기 만료 — {} ({}분 경과)", pending.incidentId, expireAfter.toMinutes())
            actionApprovalService.decide(pending.incidentId, ApprovalStatus.EXPIRED, TIMEOUT_DECIDER)
            // Slack 카드 마감·회신은 ApprovalDecided 리스너 — 버튼·API 결정과 같은 단일 지점
        } else if (pending.remindedAt == null) {
            // 표식 선기록 후 발송 — 발송 실패 시 재알림은 유실되지만 만료 안전망이 뒤에 있다
            // (반복 재알림 스팸보다 최대 1회 계약이 낫다)
            if (actionApprovalService.markReminded(pending.incidentId, now)) {
                logger.info("승인 대기 재알림 — {} ({}분 경과)", pending.incidentId, remindAfter.toMinutes())
                pending.slackMessage?.let {
                    approvalMessenger.postThreadReply(it, ApprovalMessageFactory.reminderText(remindAfter, expireAfter))
                }
            }
        }
    }

    companion object {
        /** 만료 결정 주체 — 사람 아님을 감사 컬럼이 그대로 말하게 (V3 스키마 주석 정합) */
        const val TIMEOUT_DECIDER = "system"
    }
}
