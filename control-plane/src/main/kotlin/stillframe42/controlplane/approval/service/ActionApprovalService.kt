package stillframe42.controlplane.approval.service

import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import stillframe42.controlplane.alert.event.EventPublisher
import stillframe42.controlplane.alert.event.OpsTopics
import stillframe42.controlplane.approval.model.ActionApprovalRequest
import stillframe42.controlplane.approval.model.ActionExecution
import stillframe42.controlplane.approval.model.ApprovalDecisionOutcome
import stillframe42.controlplane.approval.model.ApprovalStatus
import stillframe42.controlplane.approval.model.SlackMessageRef
import stillframe42.controlplane.approval.repository.ActionApprovalRepository
import tools.jackson.databind.json.JsonMapper

/**
 * 승인 왕복의 control-plane 측 허브 (DAY 22, ADR-0005) — 수신(ingest)·결정(decide) 모두
 * 여기로 수렴한다. 입력 채널(API·Slack 버튼)은 decide 를 부르는 어댑터일 뿐 (ADR-0006).
 *
 * 트랜잭션 경계는 이 서비스가 소유한다 (IncidentReportService 와 같은 근거).
 * decisions 발행은 결정 상태별 비대칭 (DAY 24, ADR-0005 "실행 후 발행"):
 * - rejected/expired: 전이와 발행 접수가 한 단위 — 발행 접수 실패 시 롤백해 전이만 남고
 *   재개 신호가 유실되는 상태(agent-service 영구 대기)를 만들지 않는다.
 * - approved: 전이만 커밋하고 발행은 ActionExecutionListener 가 조치 실행 후 수행 —
 *   실행 결과가 페이로드에 실려야 하는데 수십 초짜리 실행을 트랜잭션 안에 둘 수 없다.
 * 비동기 발행 실패는 이 경계 밖 — EventPublisher KDoc 의 알려진 한계 그대로.
 */
@Service
class ActionApprovalService(
    private val repository: ActionApprovalRepository,
    private val publisher: EventPublisher,
    private val events: ApplicationEventPublisher,
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
            logger.info(
                "승인 요청 저장 — {} ({}, risk={})",
                request.incidentId,
                request.actionType,
                request.riskLevel,
            )
            // Slack 승인 카드 발송은 AFTER_COMMIT 리스너 (ApprovalRequestStoredListener) —
            // 저장이 롤백되면 카드도 나가지 않는다 (IncidentReportStored 와 같은 패턴, ADR-0006)
            events.publishEvent(ApprovalRequestStored(request))
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
        if (status != ApprovalStatus.APPROVED) {
            // 실행이 없는 결정만 즉시 발행 — approved 는 조치 실행 후 ActionExecutionListener 가 발행
            val accepted = publisher.publish(
                OpsTopics.ACTIONS_DECISIONS,
                incidentId,
                decisionPayload(incidentId, status, decidedBy, decidedAt),
            )
            if (!accepted) {
                throw DecisionPublishFailedException(incidentId)
            }
        }
        logger.info("승인 결정 — {} {} (by {})", incidentId, status, decidedBy)
        // Slack 카드 마감·스레드 회신은 AFTER_COMMIT 리스너 (ApprovalDecidedListener) —
        // 입력 경로(버튼·API·타임아웃)와 무관하게 여기 한 곳에서 연결된다
        events.publishEvent(ApprovalDecided(incidentId, status, decidedBy, decidedAt))
        return ApprovalDecisionOutcome.Decided(incidentId, status, decidedBy, decidedAt)
    }

    /** 카드 발송 성공 후 좌표 보존 — AFTER_COMMIT 리스너 스레드에서 새 트랜잭션으로 연다 */
    @Transactional
    fun recordSlackMessage(incidentId: String, message: SlackMessageRef) {
        if (!repository.recordSlackMessage(incidentId, message)) {
            logger.warn("Slack 카드 좌표 기록 실패 — {} 활성 pending 없음 (발송 사이 결정 경합)", incidentId)
        }
    }

    /** 재알림 표식 — true 일 때만 호출 측이 재알림을 발송한다 (1회 규약의 트랜잭션 경계) */
    @Transactional
    fun markReminded(incidentId: String, remindedAt: Instant): Boolean =
        repository.markReminded(incidentId, remindedAt)

    /** 실행 결과 감사 기록 — 실행 리스너 스레드에서 새 트랜잭션 (물리적으로 이미 일어난 사실의 기록) */
    @Transactional
    fun recordExecution(incidentId: String, executedAt: Instant, executions: List<ActionExecution>) {
        val note = executions.joinToString(" / ") {
            val outcome = if (it.manual) "수동 안내" else if (it.ok) "성공" else "실패"
            "${it.action}: $outcome — ${it.detail}"
        }
        if (!repository.markExecuted(incidentId, executedAt, note)) {
            logger.warn("실행 결과 기록 실패 — {} approved 행 없음", incidentId)
        }
    }

    /**
     * approved 의 decisions 발행 — 실행 결과를 담아 실행 후에 발행한다 (ADR-0005).
     * 발행 접수 실패는 롤백할 본체가 없다(실행은 물리적 사실) — ERROR 로그만 남긴다.
     * 재개 신호 유실은 수동 재발행 대상 (ADR-0011 비동기 한계와 같은 계열의 알려진 한계).
     */
    fun publishExecutedDecision(
        event: ApprovalDecided,
        executions: List<ActionExecution>,
        executedAt: Instant,
    ): Boolean {
        val accepted = publisher.publish(
            OpsTopics.ACTIONS_DECISIONS,
            event.incidentId,
            decisionPayload(event.incidentId, event.status, event.decidedBy, event.decidedAt, executions, executedAt),
        )
        if (!accepted) {
            logger.error("decisions 발행 실패 — {} 재개 신호 유실 (수동 재발행 필요)", event.incidentId)
        }
        return accepted
    }

    /** ops.actions.decisions 페이로드 — 소비 측은 agent-service DecisionEventProcessor. */
    private fun decisionPayload(
        incidentId: String,
        status: String,
        decidedBy: String,
        decidedAt: Instant,
        executions: List<ActionExecution> = emptyList(),
        executedAt: Instant? = null,
    ): String = mapper.writeValueAsString(
        mapOf(
            "incident_id" to incidentId,
            "status" to status,
            "decided_by" to decidedBy,
            "decided_at" to decidedAt.toString(),
            // 실행 결과 (DAY 24, ADR-0005) — approved 는 실행 후 발행이라 채워진다, 거부/만료는 빈 목록.
            // manual = 수동 조치 안내 항목 (자동 실행 아님) — 회복 확인이 대기 예산을 늘려 잡는 근거
            "execution" to executions.map {
                mapOf("action" to it.action, "ok" to it.ok, "detail" to it.detail, "manual" to it.manual)
            },
            "executed_at" to executedAt?.toString(),
            "note" to "",
        ),
    )
}
