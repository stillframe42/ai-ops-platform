package stillframe42.controlplane.approval.repository

import java.time.Instant
import stillframe42.controlplane.approval.model.ActionApprovalRequest
import stillframe42.controlplane.approval.model.ApprovalCard
import stillframe42.controlplane.approval.model.PendingApproval
import stillframe42.controlplane.approval.model.SlackMessageRef

/**
 * 승인 저장소의 도메인 인터페이스 — 구현(JPA)과 테스트 fake 의 주입 경계
 * (IncidentReportRepository 와 같은 2단 구조). 분기 판단은 서비스 몫으로 남기고
 * 여기는 단일 호출의 정합성만 책임진다.
 */
interface ActionApprovalRepository {

    /** 활성 pending 이 없을 때만 신규 저장 — true = 신규 (재수신은 기존 행 유지, at-least-once 멱등) */
    fun insertPendingIfAbsent(request: ActionApprovalRequest): Boolean

    /** 활성 pending 이 있으면 종결 상태로 전이 — true = 전이됨 (없으면 false, 판단은 서비스가) */
    fun markDecided(incidentId: String, status: String, decidedBy: String, decidedAt: Instant): Boolean

    /** 최신 승인 행의 상태 — 없으면 null (NotFound 와 AlreadyDecided 구분 근거) */
    fun findLatestStatus(incidentId: String): String?

    /** 카드 발송 성공 후 좌표 보존 — 활성 pending 이 없으면 false (발송 사이 결정 경합) */
    fun recordSlackMessage(incidentId: String, message: SlackMessageRef): Boolean

    /** 재알림 표식 — 이미 표식이 있거나 활성 pending 이 없으면 false (재알림 1회 규약) */
    fun markReminded(incidentId: String, remindedAt: Instant): Boolean

    /** 타임아웃 스캔 — cutoff 이전에 요청된 활성 pending 전부 (스케줄러 전용) */
    fun findPendingRequestedBefore(cutoff: Instant): List<PendingApproval>

    /** 최신 승인 행의 카드 재구성 정보 — 결정 후 Slack 카드 마감(chat.update)용 */
    fun findLatestCard(incidentId: String): ApprovalCard?
}
