package stillframe42.controlplane.alert.service

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 단위 테스트 경계 — fingerprint 멱등성 규약(신규 발급/반복 병합/해소 후 재발화 분리)만 검증. */
class IncidentRegistryTest {

    private val clock = Clock.fixed(Instant.parse("2026-07-25T09:30:00Z"), ZoneOffset.UTC)

    @Test
    fun `신규 fingerprint 는 규약 형식의 인시던트 id 를 발급한다`() {
        val registry = IncidentRegistry(clock)

        val tracked = registry.track("d38f7c69cf7e2d2b", "error-rate-surge")

        assertTrue(tracked.isNew)
        assertEquals(0, tracked.mergeCount)
        // inc-{scenario}-{UTC ts} 규약(agent-service build_incident 정합) + fingerprint 앞 6자로 유일성 보강
        assertEquals("inc-error-rate-surge-20260725093000-d38f7c", tracked.incidentId)
    }

    @Test
    fun `동일 fingerprint 반복 발화는 기존 인시던트로 병합하고 횟수를 센다`() {
        val registry = IncidentRegistry(clock)
        val first = registry.track("d38f7c69cf7e2d2b", "error-rate-surge")

        val second = registry.track("d38f7c69cf7e2d2b", "error-rate-surge")
        val third = registry.track("d38f7c69cf7e2d2b", "error-rate-surge")

        assertFalse(second.isNew)
        assertEquals(first.incidentId, second.incidentId)
        assertEquals(1, second.mergeCount)
        assertEquals(2, third.mergeCount)
    }

    @Test
    fun `다른 fingerprint 는 다른 인시던트다`() {
        val registry = IncidentRegistry(clock)

        val a = registry.track("aaaaaa11", "latency-surge")
        val b = registry.track("bbbbbb22", "latency-surge")

        assertTrue(b.isNew)
        assertNotEquals(a.incidentId, b.incidentId)
    }

    @Test
    fun `resolve 는 활성 해제하고 인시던트 id 를 돌려준다 - 이후 재발화는 새 인시던트`() {
        val registry = IncidentRegistry(clock)
        val first = registry.track("d38f7c69cf7e2d2b", "memory-pressure")

        val resolvedId = registry.resolve("d38f7c69cf7e2d2b")
        val refired = registry.track("d38f7c69cf7e2d2b", "memory-pressure")

        assertEquals(first.incidentId, resolvedId)
        // 해소 후 재발화 = 새 장애 — 병합이 아니라 신규로 분리한다
        assertTrue(refired.isNew)
        assertEquals(0, refired.mergeCount)
    }

    @Test
    fun `같은 초의 resolve 후 재발화도 id 는 유일하다 - thread_id 충돌 방지`() {
        val registry = IncidentRegistry(clock)
        val first = registry.track("d38f7c69cf7e2d2b", "error-rate-surge")
        registry.resolve("d38f7c69cf7e2d2b")

        val refired = registry.track("d38f7c69cf7e2d2b", "error-rate-surge")

        // 고정 clock = 같은 초·같은 fingerprint 의 최악 케이스 — flapping 이 같은 id 를 받으면
        // 컨슈머(thread_id + is_run_complete)가 새 장애를 중복으로 오인해 건너뛴다
        assertNotEquals(first.incidentId, refired.incidentId)
    }

    @Test
    fun `미등록 fingerprint 의 resolve 는 null - 오류가 아니다`() {
        val registry = IncidentRegistry(clock)

        assertNull(registry.resolve("unknown"))
    }

    @Test
    fun `untrack 은 활성 해제한다 - 발행 실패 롤백으로 다음 발화가 신규 재발행된다`() {
        val registry = IncidentRegistry(clock)
        val first = registry.track("d38f7c69cf7e2d2b", "error-rate-surge")

        val removed = registry.untrack("d38f7c69cf7e2d2b", first.incidentId)
        val refired = registry.track("d38f7c69cf7e2d2b", "error-rate-surge")

        assertTrue(removed)
        // 발행 실패한 인시던트를 장부에 남기면 이후 발화가 전부 병합돼 재발행 주체가 소멸한다 (Exp D)
        assertTrue(refired.isNew)
        // 같은 초의 재등록도 id 는 새로 발급 — 발급 이력(issued)은 untrack 후에도 유지된다
        assertNotEquals(first.incidentId, refired.incidentId)
    }

    @Test
    fun `untrack 은 인시던트 id 불일치 시 제거하지 않는다 - 경합 보호`() {
        val registry = IncidentRegistry(clock)
        registry.track("d38f7c69cf7e2d2b", "error-rate-surge")

        val removed = registry.untrack("d38f7c69cf7e2d2b", "inc-other-id")
        val next = registry.track("d38f7c69cf7e2d2b", "error-rate-surge")

        assertFalse(removed)
        // 그 사이 resolve→재발화로 주인이 바뀐 엔트리를 오인 제거하면 안 된다
        assertFalse(next.isNew)
    }

    @Test
    fun `병합이 끼어들어도 untrack 은 같은 id 면 제거한다 - 병합은 발행하지 않으므로 미발행 상태다`() {
        val registry = IncidentRegistry(clock)
        val first = registry.track("d38f7c69cf7e2d2b", "error-rate-surge")
        registry.track("d38f7c69cf7e2d2b", "error-rate-surge")

        val removed = registry.untrack("d38f7c69cf7e2d2b", first.incidentId)
        val refired = registry.track("d38f7c69cf7e2d2b", "error-rate-surge")

        assertTrue(removed)
        assertTrue(refired.isNew)
    }
}
