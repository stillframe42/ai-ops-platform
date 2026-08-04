package stillframe42.controlplane.approval.model

/**
 * 조치 1건의 실행 결과 (DAY 24, ADR-0005) — decisions 페이로드와 execution_note 의 공통 재료.
 * manual = 자동 실행 대상이 아니라 운영자 수동 조치 안내로 처리된 항목 (2026-08-04 결정:
 * RESTART_APP 자동 실행 제외) — 이때 ok 는 "안내가 전달 대상에 올랐는가"를 뜻한다.
 */
data class ActionExecution(
    val action: String,
    val ok: Boolean,
    val detail: String,
    val manual: Boolean = false,
)
