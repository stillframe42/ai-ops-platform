package stillframe42.llmgateway.guardrail

/** 체인 최종 판정 — reason 은 로그·헤더가 아닌 감사 로그용 (주입 문구 자체는 싣지 않는다) */
data class GuardrailDecision(
    val verdict: GuardrailVerdict,
    val stage: GuardrailStage,
    val reason: String? = null,
) {
    val isClean: Boolean get() = verdict == GuardrailVerdict.CLEAN

    companion object {
        val CLEAN = GuardrailDecision(GuardrailVerdict.CLEAN, GuardrailStage.NONE)
    }
}
