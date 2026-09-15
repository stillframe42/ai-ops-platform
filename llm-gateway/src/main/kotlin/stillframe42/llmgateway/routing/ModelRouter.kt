package stillframe42.llmgateway.routing

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 라우팅 우선순위: ⓪ X-Experiment-Variant 가 정의된 실험 variant 를 지목하면 오버라이드 (태스크 일치 조건)
 * ① X-Task-Type 규칙 (미등록 태스크는 default 규칙 — 모델 결정권은 게이트웨이)
 * ② 헤더 없으면 요청 body 의 model — **게이트웨이 별칭 우선 해석** (태스크명·"default" 이면 해당 규칙:
 *    클라이언트 설정에서 프로바이더 모델명을 제거하기 위한 이름공간 — 실모델명이면 그대로 통과)
 * ③ 둘 다 없으면 default 규칙
 */
@Component
class ModelRouter(
    private val routingProperties: RoutingProperties,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun resolve(taskType: String?, requestedModel: String?, experimentVariant: String? = null): Route {
        experimentVariant?.let { header ->
            experimentRoute(taskType, header)?.let { return it }
            // 미정의 variant·태스크 불일치·형식 오류는 무시하고 규칙대로 — 임의 모델 지정 헤더는 예산·정책 우회라
            // 정의된 variant 만 허용한다 (ADR-0019). 무시 사실은 감사 목적으로 WARN 에 남긴다
            logger.warn("정의되지 않은 실험 variant 무시 — header={} task={}", header, taskType)
        }
        if (taskType != null) {
            val rule = routingProperties.rules.find { it.task == taskType } ?: routingProperties.defaultRule
            return toRoute(taskType, rule)
        }
        if (requestedModel != null) {
            val alias = routingProperties.rules.find { it.task == requestedModel }
                ?: routingProperties.defaultRule.takeIf { it.task == requestedModel }
            return alias?.let { toRoute(taskType = null, rule = it) }
                ?: Route(taskType = null, provider = Provider.ANTHROPIC, model = requestedModel, maxTokens = null)
        }
        return toRoute(taskType = null, rule = routingProperties.defaultRule)
    }

    private fun experimentRoute(taskType: String?, header: String): Route? {
        val separator = header.indexOf(':')
        if (separator <= 0 || separator == header.lastIndex) return null
        val name = header.substring(0, separator)
        val variantKey = header.substring(separator + 1)
        val experiment = routingProperties.experiments.find { it.name == name && it.task == taskType } ?: return null
        val variant = experiment.variants[variantKey] ?: return null
        return Route(
            taskType = taskType,
            provider = Provider.valueOf(variant.provider.uppercase()),
            model = variant.model,
            maxTokens = variant.maxTokens,
            variant = header,
        )
    }

    private fun toRoute(taskType: String?, rule: RoutingProperties.Rule) = Route(
        taskType = taskType,
        provider = Provider.valueOf(rule.provider.uppercase()),
        model = rule.model,
        maxTokens = rule.maxTokens,
    )
}
