package stillframe42.controlplane.approval.controller

import com.fasterxml.jackson.annotation.JsonProperty

/** 승인/거부 요청 본문 — 생략 가능. 승인 주체 미지정 시 "api" (Slack 경로는 user ID 를 넣는다) */
data class DecisionRequest(@field:JsonProperty("decided_by") val decidedBy: String? = null)
