package stillframe42.llmgateway.routing

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 태스크 유형 → 모델 라우팅 정책 — 정책은 yml 외부화.
 * ADR-0007 이 보류했던 역할별 모델 차등의 실현 지점.
 * 실험(experiments)은 `X-Experiment-Variant` 로 지목된 variant 가 규칙을 오버라이드하는 유일한 경로 —
 * 헤더로 임의 모델을 지정하면 예산·라우팅 정책이 우회되므로 여기 정의된 variant 만 허용한다 (ADR-0019 결정 ③).
 */
@ConfigurationProperties("gateway.routing")
data class RoutingProperties(
    val rules: List<Rule> = emptyList(),
    val defaultRule: Rule = Rule(task = "default", provider = "anthropic", model = "claude-sonnet-5"),
    val experiments: List<Experiment> = emptyList(),
) {
    data class Rule(
        val task: String,
        val provider: String = "anthropic",
        val model: String,
        val maxTokens: Int? = null,
    )

    /** variant 키(B·C …)는 헤더 값 `<name>:<variant>` 의 뒷부분과 그대로 대조된다 */
    data class Experiment(
        val name: String,
        val task: String,
        val variants: Map<String, Variant> = emptyMap(),
    )

    data class Variant(
        val provider: String = "anthropic",
        val model: String,
        val maxTokens: Int? = null,
    )
}
