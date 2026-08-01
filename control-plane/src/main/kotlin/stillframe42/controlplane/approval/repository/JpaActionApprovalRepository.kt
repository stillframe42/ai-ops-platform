package stillframe42.controlplane.approval.repository

import java.time.Instant
import org.springframework.stereotype.Repository
import stillframe42.controlplane.approval.entity.ActionApprovalEntity
import stillframe42.controlplane.approval.model.ActionApprovalRequest
import stillframe42.controlplane.approval.model.ApprovalStatus

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
}
