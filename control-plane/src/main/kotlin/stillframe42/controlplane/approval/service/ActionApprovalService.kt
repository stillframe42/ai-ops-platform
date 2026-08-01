package stillframe42.controlplane.approval.service

import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import stillframe42.controlplane.alert.event.EventPublisher
import stillframe42.controlplane.alert.event.OpsTopics
import stillframe42.controlplane.approval.model.ActionApprovalRequest
import stillframe42.controlplane.approval.model.ApprovalDecisionOutcome
import stillframe42.controlplane.approval.model.ApprovalStatus
import stillframe42.controlplane.approval.repository.ActionApprovalRepository
import tools.jackson.databind.json.JsonMapper

/**
 * 승인 왕복의 control-plane 측 허브 (DAY 22, ADR-0005) — 수신(ingest)·결정(decide) 모두
 * 여기로 수렴한다. 입력 채널(API·Slack 버튼)은 decide 를 부르는 어댑터일 뿐 (ADR-0006).
 *
 * 트랜잭션 경계는 이 서비스가 소유한다 (IncidentReportService 와 같은 근거) —
 * decide 는 전이(UPDATE)와 decisions 발행 접수가 한 단위: 발행 접수 실패 시 예외로
 * 롤백해 전이만 남고 재개 신호가 유실되는 상태(agent-service 영구 대기)를 만들지 않는다.
 * 비동기 발행 실패는 이 경계 밖 — EventPublisher KDoc 의 알려진 한계 그대로.
 */
@Service
class ActionApprovalService(
    private val repository: ActionApprovalRepository,
    private val publisher: EventPublisher,
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = JsonMapper.builder().build()

    @Transactional
    fun ingest(payload: String) {
        val request = ActionApprovalRequest.parse(payload)
        if (request == null) {
            logger.warn("승인 요청 페이로드 파싱 실패 — 건너뜀 (본문 {}자)", payload.length)
            return
        }
        val isNew = repository.insertPendingIfAbsent(request)
        if (isNew) {
            // Slack 승인 요청 발송은 Phase 3 (ADR-0006 Socket Mode) — 여기서 이벤트로 연결 예정
            logger.info(
                "승인 요청 저장 — {} ({}, risk={})",
                request.incidentId,
                request.actionType,
                request.riskLevel,
            )
        } else {
            logger.info("승인 요청 재수신 — {} 기존 pending 유지", request.incidentId)
        }
    }

    @Transactional
    fun decide(incidentId: String, status: String, decidedBy: String): ApprovalDecisionOutcome {
        require(status in ApprovalStatus.DECIDED) { "종결 상태가 아님: $status" }
        val decidedAt = Instant.now()
        if (!repository.markDecided(incidentId, status, decidedBy, decidedAt)) {
            val latest = repository.findLatestStatus(incidentId)
                ?: return ApprovalDecisionOutcome.NotFound
            return ApprovalDecisionOutcome.AlreadyDecided(latest)
        }
        val accepted = publisher.publish(
            OpsTopics.ACTIONS_DECISIONS,
            incidentId,
            decisionPayload(incidentId, status, decidedBy, decidedAt),
        )
        if (!accepted) {
            throw DecisionPublishFailedException(incidentId)
        }
        logger.info("승인 결정 — {} {} (by {})", incidentId, status, decidedBy)
        return ApprovalDecisionOutcome.Decided(incidentId, status, decidedBy, decidedAt)
    }

    /** ops.actions.decisions 페이로드 — 소비 측은 agent-service DecisionEventProcessor. */
    private fun decisionPayload(
        incidentId: String,
        status: String,
        decidedBy: String,
        decidedAt: Instant,
    ): String = mapper.writeValueAsString(
        mapOf(
            "incident_id" to incidentId,
            "status" to status,
            "decided_by" to decidedBy,
            "decided_at" to decidedAt.toString(),
            // 조치 실행 결과는 Phase 4 에서 채운다 (ADR-0005 — 실행 후 발행으로 확장)
            "note" to "",
        ),
    )
}
