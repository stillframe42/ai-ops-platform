package stillframe42.llmgateway.routing

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 태스크 유형 → 모델 라우팅 정책 — 정책은 yml 외부화.
 * ADR-0007 이 보류했던 역할별 모델 차등의 실현 지점.
 */
@ConfigurationProperties("gateway.routing")
data class RoutingProperties(
    val rules: List<Rule> = emptyList(),
    val defaultRule: Rule = Rule(task = "default", provider = "anthropic", model = "claude-sonnet-5"),
) {
    data class Rule(
        val task: String,
        val provider: String = "anthropic",
        val model: String,
        val maxTokens: Int? = null,
    )
}
