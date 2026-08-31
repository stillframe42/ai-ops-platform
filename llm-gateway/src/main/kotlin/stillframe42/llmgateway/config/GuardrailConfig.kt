package stillframe42.llmgateway.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import stillframe42.llmgateway.budget.BudgetGuard
import stillframe42.llmgateway.cost.CostRecorder
import stillframe42.llmgateway.guardrail.ClassifierInputGuardrail
import stillframe42.llmgateway.guardrail.GuardrailProperties
import stillframe42.llmgateway.guardrail.InputGuardrailChain
import stillframe42.llmgateway.guardrail.PatternInputGuardrail
import stillframe42.llmgateway.relay.ChatRelayService
import stillframe42.llmgateway.relay.GatewayMetrics
import stillframe42.llmgateway.routing.ModelRouter

/** 입력 가드레일 배선 — 분류기는 정책 스위치(gateway.guardrail.classifier.enabled)로 빠지고, 체인은 분류기 부재를 SUSPECT 유지로 처리한다 */
@Configuration
class GuardrailConfig {

    @Bean
    @ConditionalOnProperty("gateway.guardrail.classifier.enabled", havingValue = "true", matchIfMissing = true)
    fun classifierInputGuardrail(
        chatRelayService: ChatRelayService,
        modelRouter: ModelRouter,
        costRecorder: CostRecorder,
        budgetGuard: BudgetGuard,
        guardrailProperties: GuardrailProperties,
    ) = ClassifierInputGuardrail(chatRelayService, modelRouter, costRecorder, budgetGuard, guardrailProperties)

    @Bean
    fun inputGuardrailChain(
        patternInputGuardrail: PatternInputGuardrail,
        classifierInputGuardrail: ClassifierInputGuardrail?,
        guardrailProperties: GuardrailProperties,
        gatewayMetrics: GatewayMetrics,
    ) = InputGuardrailChain(patternInputGuardrail, classifierInputGuardrail, guardrailProperties, gatewayMetrics)
}
