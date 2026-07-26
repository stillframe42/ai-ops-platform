package stillframe42.controlplane.alert.controller

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import stillframe42.controlplane.alert.event.EventPublisher
import stillframe42.controlplane.alert.event.OpsTopics
import stillframe42.controlplane.alert.service.AlertIngestService
import stillframe42.controlplane.alert.service.IncidentRegistry

/**
 * 단위 테스트 경계 — standalone MockMvc (서블릿 컨테이너·Kafka 무의존).
 * 202 즉시 응답 규약과 수신 본문의 인계만 검증한다. @Async 프록시 동작(비동기 분리)은
 * 스프링 컨텍스트 몫이라 여기서는 동기 실행 — 비동기 자체는 E2E 에서 확인.
 */
class AlertmanagerWebhookControllerTest {

    private class RecordingPublisher : EventPublisher {
        val published = mutableListOf<Triple<String, String?, String>>()
        override fun publish(topic: String, key: String?, payload: String) {
            published += Triple(topic, key, payload)
        }
    }

    private val clock = Clock.fixed(Instant.parse("2026-07-25T09:30:00Z"), ZoneOffset.UTC)

    @Test
    fun `webhook 수신은 202 를 반환하고 본문을 수집 서비스로 넘긴다`() {
        val publisher = RecordingPublisher()
        val controller =
            AlertmanagerWebhookController(AlertIngestService(IncidentRegistry(clock), publisher, clock))
        val mvc = MockMvcBuilders.standaloneSetup(controller).build()

        mvc.perform(
            post("/webhook/alertmanager")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"version":"4","alerts":[]}"""),
        ).andExpect(status().isAccepted)

        // 빈 alerts 여도 원본 보존은 수행된다 — 본문이 그대로 인계됐다는 증거
        assertEquals(OpsTopics.ALERTS_RAW, publisher.published.single().first)
        assertEquals("""{"version":"4","alerts":[]}""", publisher.published.single().third)
    }
}
