package stillframe42.controlplane.approval.model

/**
 * 승인 상태 상수 (V3 status 컬럼 값 정합) — pending 만 활성, 나머지는 종결 상태.
 * 전이는 pending → approved | rejected | expired 단방향 (부분 유니크 인덱스가 활성 1건을 강제).
 */
object ApprovalStatus {
    const val PENDING = "pending"
    const val APPROVED = "approved"
    const val REJECTED = "rejected"
    const val EXPIRED = "expired"

    /** pending 에서 전이 가능한 종결 상태 — decide 경로의 입력 검증 근거 (expired 는 타임아웃 전이, Phase 3) */
    val DECIDED = setOf(APPROVED, REJECTED, EXPIRED)
}
