package stillframe42.llmgateway.routing

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelRouterTest {

    private val router = ModelRouter(
        RoutingProperties(
            rules = listOf(
                RoutingProperties.Rule(task = "monitoring-summary", provider = "anthropic", model = "claude-haiku-4-5", maxTokens = 2000),
                RoutingProperties.Rule(task = "code-review-critical", provider = "openai", model = "gpt-5.6-terra"),
            ),
            defaultRule = RoutingProperties.Rule(task = "default", provider = "anthropic", model = "claude-sonnet-5"),
        ),
    )

    @Test
    fun `등록 태스크는 규칙이 모델을 결정한다 - body model 무시`() {
        val route = router.resolve("monitoring-summary", requestedModel = "claude-sonnet-5")
        assertEquals(Provider.ANTHROPIC, route.provider)
        assertEquals("claude-haiku-4-5", route.model)
        assertEquals(2000, route.maxTokens)
    }

    @Test
    fun `교차 프로바이더 규칙`() {
        val route = router.resolve("code-review-critical", requestedModel = null)
        assertEquals(Provider.OPENAI, route.provider)
        assertEquals("gpt-5.6-terra", route.model)
    }

    @Test
    fun `미등록 태스크는 default 규칙 - 모델 결정권은 게이트웨이`() {
        val route = router.resolve("unknown-task", requestedModel = "claude-haiku-4-5")
        assertEquals("claude-sonnet-5", route.model)
    }

    @Test
    fun `body model 이 게이트웨이 별칭이면 규칙으로 해석 - 클라이언트는 실모델명을 모른다`() {
        assertEquals("claude-sonnet-5", router.resolve(null, requestedModel = "default").model)
        assertEquals("claude-haiku-4-5", router.resolve(null, requestedModel = "monitoring-summary").model)
    }

    @Test
    fun `헤더 없으면 body model 존중 - Phase 1 동작 유지`() {
        val route = router.resolve(null, requestedModel = "claude-haiku-4-5")
        assertEquals(Provider.ANTHROPIC, route.provider)
        assertEquals("claude-haiku-4-5", route.model)
    }

    @Test
    fun `헤더도 body model 도 없으면 default 규칙`() {
        val route = router.resolve(null, requestedModel = null)
        assertEquals("claude-sonnet-5", route.model)
    }
}
