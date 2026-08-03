package stillframe42.controlplane.approval.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import stillframe42.controlplane.approval.model.ActionApprovalRequest
import stillframe42.controlplane.approval.model.ApprovalStatus
import stillframe42.controlplane.common.jpa.AuditedEntity

/**
 * action_approvals 영속 모델 (DAY 22) — 스키마 소유는 Flyway V3, 여기는 validate 만.
 * repository 구현 전용 — 서비스·컨트롤러는 model 만 본다 (incident 와 같은 계층 규약).
 *
 * 대리 키(id identity)인 이유: 인시던트당 승인 이력이 다행으로 쌓인다 (종결 상태 보존,
 * 활성 pending 1건만 부분 유니크 인덱스가 강제 — 신규/재수신 판정은 저장소 선조회).
 */
@Entity
@Table(name = "action_approvals")
class ActionApprovalEntity(

    @Column(name = "incident_id", nullable = false)
    val incidentId: String,

    @Column(name = "action_type", nullable = false)
    val actionType: String,

    /** 조치안 페이로드 원문 — String 이 곧 JSON 문서로 바인딩된다 (jsonb, report 원칙) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "action_payload", nullable = false)
    val actionPayload: String,

    @Column(name = "risk_level")
    val riskLevel: String?,

    val confidence: Double?,

    @Column(nullable = false)
    var status: String,

    @Column(name = "requested_at", nullable = false)
    val requestedAt: Instant,

    @Column(name = "decided_at")
    var decidedAt: Instant? = null,

    @Column(name = "decided_by")
    var decidedBy: String? = null,

    @Column(name = "executed_at")
    var executedAt: Instant? = null,

    @Column(name = "execution_note")
    var executionNote: String? = null,

    /** Slack 카드 좌표 (V4) — 미발송 카드는 null 유지 (토큰 미설정·발송 실패) */
    @Column(name = "slack_channel")
    var slackChannel: String? = null,

    @Column(name = "slack_message_ts")
    var slackMessageTs: String? = null,

    /** 30분 재알림 1회 규약의 표식 (V4) — null 이면 아직 재알림 전 */
    @Column(name = "reminded_at")
    var remindedAt: Instant? = null,
) : AuditedEntity() {

    /** GENERATED ALWAYS AS IDENTITY — INSERT 에서 id 를 생략해야 하므로 Hibernate IDENTITY 전략 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    /** 전이 규약 — 결정 필드 집합을 한 곳이 소유한다 (트랜잭션 안 dirty checking 으로 반영) */
    fun decide(status: String, decidedBy: String, decidedAt: Instant) {
        this.status = status
        this.decidedBy = decidedBy
        this.decidedAt = decidedAt
    }

    /** 카드 발송 성공 후 좌표 보존 — 마감(chat.update)·스레드 회신·재알림의 대상 (DAY 23) */
    fun recordSlackMessage(channel: String, messageTs: String) {
        this.slackChannel = channel
        this.slackMessageTs = messageTs
    }

    fun markReminded(at: Instant) {
        this.remindedAt = at
    }

    companion object {
        fun pendingFrom(request: ActionApprovalRequest) = ActionApprovalEntity(
            incidentId = request.incidentId,
            actionType = request.actionType,
            actionPayload = request.raw,
            riskLevel = request.riskLevel,
            confidence = request.confidence,
            status = ApprovalStatus.PENDING,
            requestedAt = request.requestedAt,
        )
    }
}
