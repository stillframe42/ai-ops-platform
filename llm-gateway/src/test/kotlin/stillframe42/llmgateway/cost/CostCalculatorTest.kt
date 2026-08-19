package stillframe42.llmgateway.cost

import kotlin.test.Test
import kotlin.test.assertEquals

class CostCalculatorTest {

    private val calculator = CostCalculator(
        CostProperties(
            prices = listOf(
                CostProperties.ModelPrice(modelPrefix = "claude-sonnet-5", inputPerMtok = 3.0, outputPerMtok = 15.0),
                CostProperties.ModelPrice(modelPrefix = "claude-haiku-4-5", inputPerMtok = 1.0, outputPerMtok = 5.0),
            ),
        ),
    )

    @Test
    fun `등록 모델은 입력·출력 단가로 비용을 계산한다`() {
        // haiku: 입력 100k × $1/MTok + 출력 200k × $5/MTok = 0.1 + 1.0
        assertEquals(1.1, calculator.costOf("claude-haiku-4-5", promptTokens = 100_000, completionTokens = 200_000), 1e-9)
    }

    @Test
    fun `프로바이더가 붙인 날짜 접미 모델명도 접두 매칭으로 흡수한다`() {
        // 실측 (DAY 31): saved_tokens 의 model 라벨은 claude-haiku-4-5-20251001 형태
        assertEquals(1.1, calculator.costOf("claude-haiku-4-5-20251001", promptTokens = 100_000, completionTokens = 200_000), 1e-9)
    }

    @Test
    fun `단가 미등록 모델은 0 으로 계상한다`() {
        assertEquals(0.0, calculator.costOf("gpt-5.6-terra", promptTokens = 1_000, completionTokens = 1_000), 1e-9)
    }
}
