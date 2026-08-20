package stillframe42.llmgateway.fallback

import stillframe42.llmgateway.api.ChatCompletionResponse
import stillframe42.llmgateway.routing.Route

/** 중계 결과 + 실제 사용된 라우트 — 폴백 시 비용 기록·캐시 판단이 원 라우트가 아닌 이 라우트를 본다 */
data class RelayOutcome(
    val response: ChatCompletionResponse,
    val route: Route,
    val fallback: FallbackStatus,
)
