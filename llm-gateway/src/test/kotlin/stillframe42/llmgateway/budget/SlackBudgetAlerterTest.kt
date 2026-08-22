package stillframe42.llmgateway.budget

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.web.client.RestClient

class SlackBudgetAlerterTest {

    private val registry = SimpleMeterRegistry()

    @Test
    fun `웹훅 URL 이 있으면 활성 게이지 1`() {
        SlackBudgetAlerter("https://hooks.slack.com/services/T000/B000/x", RestClient.builder(), registry)

        assertEquals(1.0, registry.get("gateway.alert.slack.enabled").gauge().value())
    }

    @Test
    fun `웹훅 URL 미설정이면 활성 게이지 0 - 경고가 로그로만 대체되는 상태의 관측 수단`() {
        SlackBudgetAlerter("", RestClient.builder(), registry)

        assertEquals(0.0, registry.get("gateway.alert.slack.enabled").gauge().value())
    }
}
