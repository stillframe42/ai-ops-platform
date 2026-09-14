package stillframe42.controlplane.alert.service

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import stillframe42.controlplane.messaging.EventPublisher
import stillframe42.controlplane.messaging.OpsTopics
import tools.jackson.databind.json.JsonMapper


import stillframe42.controlplane.incident.service.IncidentRegistry
/**
 * 단위 테스트 경계 — Kafka 무의존. 발행 기록용 fake 로 "무엇이 어느 토픽에 어떤 key 로
 * 실렸는가"(와이어 규약)만 검증한다. 실제 브로커 왕복은 E2E(확인 기준)에서.
 */
class AlertIngestServiceTest {

    private class RecordingPublisher : EventPublisher {
        val published = mutableListOf<Triple<String, String?, String>>()

        /** 여기 담긴 토픽으로의 발행은 동기 실패를 흉내낸다 (기록은 시도 기준 — 와이어 검증용). */
        val failOn = mutableSetOf<String>()

        override fun publish(topic: String, key: String?, payload: String): Boolean {
            published += Triple(topic, key, payload)
            return topic !in failOn
        }

        fun topics() = published.map { it.first }
        fun onTopic(topic: String) = published.filter { it.first == topic }
    }

    private val mapper = JsonMapper.builder().build()
    private val clock = Clock.fixed(Instant.parse("2026-07-25T09:30:00Z"), ZoneOffset.UTC)

    private fun service(publisher: RecordingPublisher) =
        AlertIngestService(IncidentRegistry(clock), publisher, clock)

    private fun firingPayload(
        alertname: String = "TargetAppHighErrorRate",
        fingerprint: String = "d38f7c69cf7e2d2b",
        status: String = "firing",
    ) = """
        {
          "version": "4",
          "groupKey": "{}:{alertname=\"$alertname\"}",
          "status": "$status",
          "receiver": "control-plane-webhook",
          "alerts": [
            {
              "status": "$status",
              "labels": {"alertname": "$alertname", "severity": "critical", "job": "target-app"},
              "annotations": {"summary": "5xx 에러율 10% 초과"},
              "startsAt": "2026-07-25T09:28:00Z",
              "endsAt": "0001-01-01T00:00:00Z",
              "fingerprint": "$fingerprint"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `firing 수신 시 원본은 raw 로, 정규화 인시던트는 incidents 로 발행한다`() {
        val publisher = RecordingPublisher()

        service(publisher).ingest(firingPayload())

        assertEquals(listOf(OpsTopics.ALERTS_RAW, OpsTopics.INCIDENTS), publisher.topics())

        val (_, key, payload) = publisher.onTopic(OpsTopics.INCIDENTS).single()
        val event = mapper.readTree(payload)
        // key = incident_id — 같은 인시던트의 이벤트가 같은 파티션(순서 보장)에 실린다
        assertEquals(event["incident_id"].asString(), key)
        assertEquals("error-rate-surge", event["scenario"].asString())
        assertEquals("TargetAppHighErrorRate", event["alert_name"].asString())
        assertEquals("critical", event["severity"].asString())
        assertEquals("5xx 에러율 10% 초과", event["summary"].asString())
        assertEquals("d38f7c69cf7e2d2b", event["fingerprint"].asString())
        assertEquals("firing", event["status"].asString())
        assertEquals("2026-07-25T09:28:00Z", event["starts_at"].asString())
        assertEquals("2026-07-25T09:30:00Z", event["occurred_at"].asString())
        assertEquals(0, event["merge_count"].asInt())
        assertTrue(event["incident_id"].asString().startsWith("inc-error-rate-surge-"))
    }

    @Test
    fun `동일 fingerprint 반복 발화는 incidents 에 재발행하지 않는다 - 병합`() {
        val publisher = RecordingPublisher()
        val service = service(publisher)

        service.ingest(firingPayload())
        service.ingest(firingPayload())

        // 원본은 매번 보존, 인시던트 이벤트는 최초 1건만 (repeat_interval 재전송 대응)
        assertEquals(2, publisher.onTopic(OpsTopics.ALERTS_RAW).size)
        assertEquals(1, publisher.onTopic(OpsTopics.INCIDENTS).size)
    }

    @Test
    fun `resolved 수신은 활성 해제 - 재발화는 새 인시던트로 발행된다`() {
        val publisher = RecordingPublisher()
        val service = service(publisher)

        service.ingest(firingPayload())
        service.ingest(firingPayload(status = "resolved"))
        service.ingest(firingPayload())

        val incidents = publisher.onTopic(OpsTopics.INCIDENTS)
        assertEquals(2, incidents.size)
        val firstId = mapper.readTree(incidents[0].third)["incident_id"].asString()
        val secondId = mapper.readTree(incidents[1].third)["incident_id"].asString()
        assertNotEquals(firstId, secondId)
    }

    @Test
    fun `매핑 없는 alertname 은 raw 만 보존하고 인시던트화하지 않는다`() {
        val publisher = RecordingPublisher()

        service(publisher).ingest(firingPayload(alertname = "UnknownAlert"))

        assertEquals(1, publisher.onTopic(OpsTopics.ALERTS_RAW).size)
        assertEquals(0, publisher.onTopic(OpsTopics.INCIDENTS).size)
    }

    @Test
    fun `인시던트 발행 실패 시 활성 해제한다 - 다음 발화가 병합이 아니라 신규 재발행이다`() {
        val publisher = RecordingPublisher()
        val service = service(publisher)

        publisher.failOn += OpsTopics.INCIDENTS
        service.ingest(firingPayload())
        publisher.failOn.clear()
        service.ingest(firingPayload())

        // Exp D 유실 창 해소안 ① — 실패한 발행을 장부에 남기면 이후 발화 전부 병합돼 재발행 주체가 소멸
        val incidents = publisher.onTopic(OpsTopics.INCIDENTS)
        assertEquals(2, incidents.size)
        val firstId = mapper.readTree(incidents[0].third)["incident_id"].asString()
        val secondId = mapper.readTree(incidents[1].third)["incident_id"].asString()
        assertNotEquals(firstId, secondId)
    }

    @Test
    fun `파싱 불가 본문도 raw 보존은 수행하고 예외를 던지지 않는다`() {
        val publisher = RecordingPublisher()

        service(publisher).ingest("not-json{{{")

        // 원본 보존이 raw 토픽의 존재 이유 — 정규화 실패와 독립이어야 한다
        assertEquals(1, publisher.onTopic(OpsTopics.ALERTS_RAW).size)
        assertEquals(0, publisher.onTopic(OpsTopics.INCIDENTS).size)
    }
}
