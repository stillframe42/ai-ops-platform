package stillframe42.llmgateway.guardrail

/** mode=block 정책에서 FLAGGED 요청 거부 — 핸들러가 판정 헤더를 실어 400 으로 변환한다 */
class GuardrailBlockedException(val decision: GuardrailDecision) :
    RuntimeException("입력 가드레일이 prompt injection 으로 판정한 요청입니다 (${decision.stage.name.lowercase()})")
