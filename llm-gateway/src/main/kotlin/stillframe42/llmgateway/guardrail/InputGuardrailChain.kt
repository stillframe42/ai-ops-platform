package stillframe42.llmgateway.guardrail

import org.slf4j.LoggerFactory
import stillframe42.llmgateway.api.ChatCompletionRequest
import stillframe42.llmgateway.relay.GatewayMetrics

/**
 * 입력 가드레일 체인 (ADR-0015 가 예약한 확장 지점의 실물) — 채팅 요청의 비신뢰 메시지(user·tool)를
 * 1차 패턴 → (SUSPECT 만) 2차 분류기 → 정책(플래깅/차단) 순으로 판정한다.
 * system·assistant 메시지는 대상이 아니다 — system 은 인증된 클라이언트(에이전트 코드)의 소유, assistant 는 모델 출력.
 * 판정은 요청 단위 하나 — 메시지별 최악값을 취한다.
 */
class InputGuardrailChain(
    private val patternInputGuardrail: PatternInputGuardrail,
    private val classifierInputGuardrail: ClassifierInputGuardrail?,
    private val guardrailProperties: GuardrailProperties,
    private val gatewayMetrics: GatewayMetrics,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun evaluate(request: ChatCompletionRequest): GuardrailDecision {
        if (!guardrailProperties.enabled) return GuardrailDecision.CLEAN

        val texts = request.messages.filter { it.role in SCANNED_ROLES }.map { it.contentText() }.filter { it.isNotBlank() }
        val patternDecisions = texts.map { patternInputGuardrail.evaluate(it) }
        var decision = patternDecisions.maxByOrNull { it.verdict.ordinal } ?: GuardrailDecision.CLEAN
        gatewayMetrics.guardrail(GuardrailStage.PATTERN, decision.verdict)

        if (decision.verdict == GuardrailVerdict.SUSPECT && classifierInputGuardrail != null) {
            val suspects = texts.filterIndexed { i, _ -> patternDecisions[i].verdict == GuardrailVerdict.SUSPECT }
            decision = classifierInputGuardrail.classify(suspects.joinToString("\n---\n"))
            gatewayMetrics.guardrail(GuardrailStage.CLASSIFIER, decision.verdict)
        }

        if (decision.verdict == GuardrailVerdict.FLAGGED && guardrailProperties.mode == GuardrailProperties.Mode.BLOCK) {
            decision = decision.copy(verdict = GuardrailVerdict.BLOCKED)
            gatewayMetrics.guardrail(GuardrailStage.POLICY, decision.verdict)
        }
        if (!decision.isClean) {
            logger.warn("입력 가드레일 판정 {} (stage={}, reason={})", decision.verdict, decision.stage, decision.reason)
        }
        return decision
    }

    companion object {
        val SCANNED_ROLES = setOf("user", "tool")
    }
}
