package stillframe42.controlplane.approval.repository

import java.time.Instant
import org.springframework.stereotype.Repository
import stillframe42.controlplane.approval.entity.ActionApprovalEntity
import stillframe42.controlplane.approval.model.ActionApprovalRequest
import stillframe42.controlplane.approval.model.ApprovalCard
import stillframe42.controlplane.approval.model.ApprovalStatus
import stillframe42.controlplane.approval.model.PendingApproval
import stillframe42.controlplane.approval.model.SlackMessageRef

/**
 * ActionApprovalRepository 의 JPA 구현 (DAY 22). 트랜잭션 경계는 서비스가 소유 —
 * 특히 markDecided 의 dirty checking 전이는 서비스 트랜잭션 안이라는 전제가 필요하다
 * (트랜잭션 없이 부르면 조회 즉시 준영속이 되어 전이가 조용히 유실 — incident 저장소와 같은 근거).
 *
 * 선조회 분기의 경합은 이중 방어: @KafkaListener 기본 동시성 1 + 같은 incident_id 는 같은
 * 파티션(순서 보장)이 1차, 부분 유니크 인덱스(uq_action_approvals_pending)가 2차 —
 * 경합 삽입은 DataIntegrityViolationException 으로 터지고 컨슈머 에러 핸들러의
 * non-retryable 목록이 즉시 건너뛴다 (KafkaConsumerConfig).
 */
@Repository
class JpaActionApprovalRepository(
    private val entityRepository: ActionApprovalEntityRepository,
) : ActionApprovalRepository {

    override fun insertPendingIfAbsent(request: ActionApprovalRequest): Boolean {
        val pending = entityRepository.findByIncidentIdAndStatus(request.incidentId, ApprovalStatus.PENDING)
        if (pending != null) {
            return false // 재수신 — 기존 pending 유지 (요청서 갱신 없음: 먼저 온 요청이 승인 대상)
        }
        entityRepository.save(ActionApprovalEntity.pendingFrom(request))
        return true
    }

    override fun markDecided(
        incidentId: String,
        status: String,
        decidedBy: String,
        decidedAt: Instant,
    ): Boolean {
        val pending = entityRepository.findByIncidentIdAndStatus(incidentId, ApprovalStatus.PENDING)
            ?: return false
        // 트랜잭션 안 dirty checking — 변경 감지로 UPDATE 가 나간다 (명시 save 불필요)
        pending.decide(status, decidedBy, decidedAt)
        return true
    }

    override fun findLatestStatus(incidentId: String): String? =
        entityRepository.findFirstByIncidentIdOrderByIdDesc(incidentId)?.status

    override fun recordSlackMessage(incidentId: String, message: SlackMessageRef): Boolean {
        val pending = entityRepository.findByIncidentIdAndStatus(incidentId, ApprovalStatus.PENDING)
            ?: return false // 발송 왕복 사이에 결정이 끝난 경합 — 마감 리스너가 카드를 못 찾는 건 수용
        pending.recordSlackMessage(message.channel, message.messageTs)
        return true
    }

    override fun markReminded(incidentId: String, remindedAt: Instant): Boolean {
        val pending = entityRepository.findByIncidentIdAndStatus(incidentId, ApprovalStatus.PENDING)
            ?: return false
        if (pending.remindedAt != null) {
            return false // 재알림 1회 규약 — 표식이 이미 있으면 반복하지 않는다
        }
        pending.markReminded(remindedAt)
        return true
    }

    override fun findPendingRequestedBefore(cutoff: Instant): List<PendingApproval> =
        entityRepository.findByStatusAndRequestedAtBefore(ApprovalStatus.PENDING, cutoff)
            .map { PendingApproval(it.incidentId, it.requestedAt, it.remindedAt, it.slackMessageOrNull()) }

    override fun findLatestCard(incidentId: String): ApprovalCard? =
        entityRepository.findFirstByIncidentIdOrderByIdDesc(incidentId)?.let { entity ->
            // 카드 내용은 저장된 페이로드 원문에서 재파싱 — 저장 시 한 번 통과한 본문이라 실패는 예외적
            ActionApprovalRequest.parse(entity.actionPayload)
                ?.let { ApprovalCard(it, entity.slackMessageOrNull()) }
        }

    override fun markExecuted(incidentId: String, executedAt: Instant, note: String): Boolean {
        val latest = entityRepository.findFirstByIncidentIdOrderByIdDesc(incidentId)
            ?: return false
        if (latest.status != ApprovalStatus.APPROVED) {
            return false // 실행 기록은 approved 행에만 — 그 밖의 상태는 실행 자체가 없어야 한다
        }
        latest.recordExecution(executedAt, note)
        return true
    }

    private fun ActionApprovalEntity.slackMessageOrNull(): SlackMessageRef? {
        val channel = slackChannel ?: return null
        val messageTs = slackMessageTs ?: return null
        return SlackMessageRef(channel, messageTs)
    }
}
