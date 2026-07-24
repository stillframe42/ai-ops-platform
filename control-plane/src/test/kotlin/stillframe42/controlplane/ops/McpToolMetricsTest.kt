package stillframe42.controlplane.ops

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import tools.jackson.databind.json.JsonMapper

/** 단위 테스트 경계 — 명시적 계측 헬퍼의 outcome 분류 규약 검증 (프록시·AOP 무의존). */
class McpToolMetricsTest {

    private val registry = SimpleMeterRegistry()
    private val metrics = McpToolMetrics(registry)

    private fun timerCount(tool: String, outcome: String): Long =
        registry.find("mcp.tool.calls").tag("tool", tool).tag("outcome", outcome).timer()?.count() ?: 0

    @Test
    fun `정상 응답은 success 로 기록되고 결과를 그대로 반환한다`() {
        val result = metrics.record("sampleOk") { """{"result":"ok"}""" }

        assertEquals("""{"result":"ok"}""", result)
        assertEquals(1, timerCount("sampleOk", "success"))
    }

    @Test
    fun `error 필드 응답은 degraded 로 기록된다 - isError false 강등 관례의 가시화`() {
        metrics.record("sampleDegraded") { """{"error":"검색 백엔드 비활성"}""" }

        assertEquals(1, timerCount("sampleDegraded", "degraded"))
        assertEquals(0, timerCount("sampleDegraded", "success"))
    }

    @Test
    fun `예외는 failure 로 기록하고 그대로 전파한다`() {
        assertFailsWith<IllegalStateException> {
            metrics.record<String>("sampleFailing") { throw IllegalStateException("예상 밖 실패") }
        }

        assertEquals(1, timerCount("sampleFailing", "failure"))
    }

    @Test
    fun `JSON 이 아닌 응답은 강등 판정 대상이 아니다`() {
        metrics.record("samplePlain") { "plain text" }

        assertEquals(1, timerCount("samplePlain", "success"))
    }

    @Test
    fun `연속 호출은 같은 타이머에 누적된다`() {
        repeat(3) { metrics.record("sampleOk") { "{}" } }

        assertEquals(3, timerCount("sampleOk", "success"))
    }
}
