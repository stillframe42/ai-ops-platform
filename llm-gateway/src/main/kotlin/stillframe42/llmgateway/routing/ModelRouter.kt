package stillframe42.llmgateway.routing

import org.springframework.stereotype.Component

enum class Provider { ANTHROPIC, OPENAI }

data class Route(
    val taskType: String?,
    val provider: Provider,
    val model: String,
    val maxTokens: Int?,
)

/**
 * 라우팅 우선순위: ① X-Task-Type 규칙 (미등록 태스크는 default 규칙 — 모델 결정권은 게이트웨이)
 * ② 헤더 없으면 요청 body 의 model — **게이트웨이 별칭 우선 해석** (태스크명·"default" 이면 해당 규칙:
 *    클라이언트 설정에서 프로바이더 모델명을 제거하기 위한 이름공간 — 실모델명이면 그대로 통과)
 * ③ 둘 다 없으면 default 규칙
 */
@Component
class ModelRouter(
    private val properties: RoutingProperties,
) {

    fun resolve(taskType: String?, requestedModel: String?): Route {
        if (taskType != null) {
            val rule = properties.rules.find { it.task == taskType } ?: properties.defaultRule
            return toRoute(taskType, rule)
        }
        if (requestedModel != null) {
            val alias = properties.rules.find { it.task == requestedModel }
                ?: properties.defaultRule.takeIf { it.task == requestedModel }
            return alias?.let { toRoute(taskType = null, rule = it) }
                ?: Route(taskType = null, provider = Provider.ANTHROPIC, model = requestedModel, maxTokens = null)
        }
        return toRoute(taskType = null, rule = properties.defaultRule)
    }

    private fun toRoute(taskType: String?, rule: RoutingProperties.Rule) = Route(
        taskType = taskType,
        provider = Provider.valueOf(rule.provider.uppercase()),
        model = rule.model,
        maxTokens = rule.maxTokens,
    )
}
