package stillframe42.llmgateway

import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.boot.test.context.SpringBootTest
import stillframe42.llmgateway.budget.BudgetProperties
import stillframe42.llmgateway.guardrail.GuardrailProperties
import stillframe42.llmgateway.cost.CostProperties
import stillframe42.llmgateway.routing.RoutingProperties

@SpringBootTest
class LlmGatewayApplicationTests {

	@Test
	fun contextLoads() {
	}

	// 주입 RestClient.Builder 의 공통 타임아웃 원천 — yml(spring.http.clients.*) 바인딩 검증
	// (8/19: 사용처별 requestFactory 조립 제거의 전제가 이 중앙 설정)
	@Autowired
	lateinit var httpClientSettings: HttpClientSettings

	@Test
	fun `HTTP 클라이언트 공통 타임아웃이 yml 에서 바인딩된다`() {
		assertEquals(Duration.ofSeconds(3), httpClientSettings.connectTimeout())
		assertEquals(Duration.ofSeconds(5), httpClientSettings.readTimeout())
	}

	// 도메인 정책은 gateway.yml 분리 (8/22) — spring.config.import 가 무너지면 각 프로퍼티가
	// 클래스 기본값(빈 목록·null 한도)으로 조용히 조립되므로, 대표 값으로 import 성립을 고정한다
	@Autowired
	lateinit var routingProperties: RoutingProperties

	@Autowired
	lateinit var costProperties: CostProperties

	@Autowired
	lateinit var budgetProperties: BudgetProperties

	@Autowired
	lateinit var guardrailProperties: GuardrailProperties

	@Test
	fun `도메인 정책이 분리 파일(gateway yml)에서 바인딩된다`() {
		assertTrue(routingProperties.rules.any { it.task == "monitoring-summary" && it.model == "claude-haiku-4-5" })
		assertTrue(costProperties.prices.any { it.modelPrefix == "gpt-5.6-terra" })
		assertEquals(5.0, budgetProperties.dailyLimitUsd)
		assertTrue(routingProperties.rules.any { it.task == "guardrail-classify" && it.maxTokens == 5 })
		assertEquals(GuardrailProperties.Mode.FLAG, guardrailProperties.mode)
	}

	// Map 키(variant 이름 "B")는 대문자 그대로 바인딩돼야 헤더 값 `<name>:B` 와 대조된다 — 완화 바인딩이 소문자로 바꾸면 실험이 조용히 무효
	@Test
	fun `실험 variant 가 gateway yml 에서 variant 키 원문 그대로 바인딩된다`() {
		val experiment = routingProperties.experiments.single { it.name == "analysis-model-haiku" }
		assertEquals("root-cause-analysis", experiment.task)
		assertEquals("claude-haiku-4-5", experiment.variants["B"]?.model)
		assertEquals("anthropic", experiment.variants["B"]?.provider)
	}
}
