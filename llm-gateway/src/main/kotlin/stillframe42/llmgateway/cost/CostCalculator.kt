package stillframe42.llmgateway.cost

import org.springframework.stereotype.Component

/** 토큰 사용량 → USD 환산. 단가 미등록 모델은 0 계상 (기록 누락보다 저계상이 관측에 안전). */
@Component
class CostCalculator(
    private val properties: CostProperties,
) {

    fun costOf(model: String, promptTokens: Int, completionTokens: Int): Double {
        val price = properties.prices.find { model.startsWith(it.modelPrefix) } ?: return 0.0
        return promptTokens * price.inputPerMtok / MTOK + completionTokens * price.outputPerMtok / MTOK
    }

    companion object {
        private const val MTOK = 1_000_000.0
    }
}
