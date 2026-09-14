package stillframe42.controlplane.evaluation.model

/** 멱등 저장 결과 — id 는 저품질 알림이 검토 링크를 만들 때 쓴다. isNew=false 는 재전달 갱신 */
data class UpsertResult(val id: Long, val isNew: Boolean)
