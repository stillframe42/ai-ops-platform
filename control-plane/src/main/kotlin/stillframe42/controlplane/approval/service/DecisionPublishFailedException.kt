package stillframe42.controlplane.approval.service

/** decisions 발행 실패 — 호출 측(트랜잭션 밖)이 503 등 재시도 가능 신호로 매핑한다. */
class DecisionPublishFailedException(incidentId: String) :
    RuntimeException("승인 결정 발행 실패 — $incidentId (재시도 필요)")
