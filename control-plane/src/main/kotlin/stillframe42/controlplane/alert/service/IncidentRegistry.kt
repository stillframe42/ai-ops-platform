package stillframe42.controlplane.alert.service

import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component

/**
 * fingerprint 기반 멱등성 (DAY 17) — Alertmanager repeat_interval(30m) 재전송이
 * 새 인시던트를 만들지 않게 활성 인시던트를 fingerprint 로 추적한다.
 *
 * - 같은 fingerprint 발화: 기존 인시던트로 병합 (mergeCount 증가)
 * - resolved 수신: 활성 해제 — 이후 재발화는 새 장애로 분리
 *
 * 인메모리 보관 — 재기동 시 활성 목록이 비는 한계는 알고 수용한다: 최악 케이스가
 * "발화 중 인시던트의 중복 발행" 1건이고, 컨슈머(DAY 19)의 thread_id + is_run_complete
 * 차단이 2차 방어선이다. 영속화는 DB 스키마가 생기는 DAY 20 소재.
 */
@Component
class IncidentRegistry(private val clock: Clock = Clock.systemUTC()) {

    data class Tracked(val incidentId: String, val isNew: Boolean, val mergeCount: Int)

    private data class Active(val incidentId: String, val mergeCount: Int)

    private val active = ConcurrentHashMap<String, Active>()

    // 발급 이력 — 같은 초의 resolve→재발화(flapping)가 같은 id 를 받으면 thread_id 가 충돌해
    // 컨슈머가 새 장애를 중복으로 오인한다. 프로세스 수명 동안 인시던트 수만큼만 자란다
    private val issued = ConcurrentHashMap.newKeySet<String>()

    fun track(fingerprint: String, scenario: String): Tracked {
        var created = false
        val entry = active.compute(fingerprint) { _, existing ->
            if (existing == null) {
                created = true
                Active(newIncidentId(scenario, fingerprint), mergeCount = 0)
            } else {
                existing.copy(mergeCount = existing.mergeCount + 1)
            }
        }!!
        return Tracked(entry.incidentId, created, entry.mergeCount)
    }

    /** 활성 해제 — 추적 중이 아니면 null (오류 아님: 재기동 후의 resolved 수신 등). */
    fun resolve(fingerprint: String): String? = active.remove(fingerprint)?.incidentId

    // inc-{scenario}-{UTC ts} 는 agent-service build_incident 규약 — 같은 초의 fingerprint
    // 충돌(그룹 내 다중 alert)을 앞 6자 접미로 분리한다
    private fun newIncidentId(scenario: String, fingerprint: String): String {
        val ts = TS_FORMAT.format(clock.instant())
        val base = "inc-$scenario-$ts-${fingerprint.take(6)}"
        var candidate = base
        var seq = 2
        while (!issued.add(candidate)) {
            candidate = "$base-${seq++}"
        }
        return candidate
    }

    companion object {
        // Instant 는 zone 정보가 없어 formatter 쪽에 UTC 를 지정해야 format 가능
        private val TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)
    }
}
