package stillframe42.llmgateway.guardrail

/**
 * 가드레일 판정 — 응답 헤더 X-Gateway-Guardrail·메트릭 라벨의 원천.
 * SUSPECT 는 휴리스틱만 걸리고 2차 판정이 없을 때(분류기 비활성·오류) 남는 중간값 — 관측은 되지만 확정은 아니다.
 */
enum class GuardrailVerdict { CLEAN, SUSPECT, FLAGGED, BLOCKED }
