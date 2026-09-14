package stillframe42.controlplane.alert.service

import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import stillframe42.controlplane.messaging.EventPublisher
import stillframe42.controlplane.incident.event.IncidentEvent
import stillframe42.controlplane.incident.event.IncidentStatus
import stillframe42.controlplane.messaging.OpsTopics
import stillframe42.controlplane.quality.model.QualitySloAlert
import stillframe42.controlplane.quality.notify.QualitySloNotifier
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper


import stillframe42.controlplane.incident.service.IncidentRegistry
/**
 * Alertmanager webhook 수집 (DAY 17) — 원본 보존 → 정규화 → 멱등 처리 → 발행.
 *
 * - 원본은 파싱 성패와 무관하게 ops.alerts.raw 로 보존 (정규화 버그 시 재생성 근거)
 * - firing 만 인시던트화: alertname → scenario 역매핑 (agent-service INCIDENT_PRESETS 정합),
 *   매핑 없는 alert 는 raw 보존만 하고 건너뛴다
 * - 반복 발화는 IncidentRegistry 병합 — ops.incidents 는 "새 인시던트" 스트림으로 유지
 * - resolved 는 활성 해제만 — 해소 이벤트 발행은 소비처가 생길 때 재검토
 * - `kind=quality` 라벨(품질 SLO 룰)은 인시던트화하지 않고 Slack 만 — 대상이 target-app 이 아니라 분석 품질이라
 *   에이전트를 돌릴 일이 없다. raw 보존은 다른 alert 와 같다
 */
@Service
class AlertIngestService(
    private val incidentRegistry: IncidentRegistry,
    private val eventPublisher: EventPublisher,
    private val qualitySloNotifier: QualitySloNotifier,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = JsonMapper.builder().build()

    /** webhook 스레드에서 분리해 202 응답을 지연시키지 않는다 — Alertmanager 재전송 유발 방지. */
    @Async
    fun ingestAsync(rawBody: String) = ingest(rawBody)

    fun ingest(rawBody: String) {
        val root = runCatching { mapper.readTree(rawBody) }.getOrNull()
        eventPublisher.publish(OpsTopics.ALERTS_RAW, root?.path("groupKey")?.stringOrNull(), rawBody)
        if (root == null) {
            logger.warn("Alertmanager 본문 파싱 실패 — raw 보존만 수행 (본문 {}자)", rawBody.length)
            return
        }
        root.path("alerts").forEach { handleAlert(it) }
    }

    private fun handleAlert(alert: JsonNode) {
        if (QualitySloAlert.isQuality(alert)) {
            val qualitySloAlert = QualitySloAlert.from(alert) ?: return
            logger.info("품질 알림 {} — {} (인시던트화 없음, Slack 만)", qualitySloAlert.status, qualitySloAlert.alertName)
            qualitySloNotifier.notify(qualitySloAlert)
            return
        }
        val fingerprint = alert.path("fingerprint").stringOrNull() ?: run {
            logger.warn("fingerprint 없는 alert — 멱등 처리 불가로 건너뜀")
            return
        }
        val status = alert.path("status").stringOrNull()
        when (IncidentStatus.fromWire(status)) {
            IncidentStatus.FIRING -> fire(alert, fingerprint)
            IncidentStatus.RESOLVED -> incidentRegistry.resolve(fingerprint)?.also {
                logger.info("인시던트 해소 — {} (fingerprint {})", it, fingerprint)
            }
            null -> logger.warn("알 수 없는 alert status '{}' — 건너뜀", status)
        }
    }

    private fun fire(alert: JsonNode, fingerprint: String) {
        val alertName = alert.path("labels").path("alertname").stringOrNull() ?: return
        val scenario = SCENARIO_BY_ALERT[alertName] ?: run {
            logger.warn("scenario 매핑 없는 alertname '{}' — raw 보존만 (인시던트화 생략)", alertName)
            return
        }
        val tracked = incidentRegistry.track(fingerprint, scenario)
        if (!tracked.isNew) {
            logger.info("반복 발화 병합 — {} ({}회째)", tracked.incidentId, tracked.mergeCount)
            return
        }
        val event = IncidentEvent(
            incidentId = tracked.incidentId,
            fingerprint = fingerprint,
            scenario = scenario,
            alertName = alertName,
            severity = alert.path("labels").path("severity").stringOrNull(),
            summary = alert.path("annotations").path("summary").stringOrNull(),
            status = IncidentStatus.FIRING,
            startsAt = alert.path("startsAt").stringOrNull(),
            occurredAt = clock.instant(),
            mergeCount = tracked.mergeCount,
        )
        // key = incident_id — 인시던트 단위 파티션 고정(순서 보장), 토픽 설계와 한 몸
        val published = eventPublisher.publish(OpsTopics.INCIDENTS, tracked.incidentId, mapper.writeValueAsString(event.toWire()))
        if (!published) {
            // Exp D 유실 창 해소안 ① — 활성 해제로 다음 발화(repeat_interval 재전송 포함)를 재시도 주체로
            incidentRegistry.untrack(fingerprint, tracked.incidentId)
            logger.warn("인시던트 발행 실패 — 활성 해제, 다음 발화가 재발행한다: {} ({})", tracked.incidentId, alertName)
            return
        }
        // "접수"인 이유: 이 시점 확정은 동기 접수까지 — 비동기 전달 실패는 eventPublisher 의
        // whenComplete WARN(key=incident_id)으로만 관측된다 (EventPublisher KDoc 계약 한계)
        logger.info("인시던트 발행 접수 — {} ({})", tracked.incidentId, alertName)
    }

    private fun JsonNode.stringOrNull(): String? = if (isString) stringValue() else null

    companion object {
        /** agent-service INCIDENT_PRESETS 의 역매핑 — Alert Rule 이름은 infra/prometheus/rules 기준. */
        val SCENARIO_BY_ALERT = mapOf(
            "TargetAppHeapUsageHigh" to "memory-pressure",
            "TargetAppHighErrorRate" to "error-rate-surge",
            "TargetAppHighLatency" to "latency-surge",
        )
    }
}
