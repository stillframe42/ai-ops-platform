package stillframe42.controlplane.approval.repository

import java.time.Instant
import stillframe42.controlplane.approval.model.ActionApprovalRequest

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
}
