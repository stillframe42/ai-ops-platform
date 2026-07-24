package stillframe42.controlplane.ops

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * MCP 도구 호출 계측 (DAY 17) — Spring AI 2.0.0 MCP 모듈에는 Micrometer 관측이 없어(실측) 직접 계측한다.
 *
 * AOP 가 아닌 명시적 계측을 택했다: 관측 용도에 위버(aspectjweaver) 의존을 추가할 이유가 없고
 * (Boot 4 는 starter-aop 도 제공하지 않음), 도구 3종 규모에선 각 메서드가 record 로 감싸는 쪽이
 * 프록시 계층 없이 단순하다. 신규 도구의 계측 누락은 OpsToolProviderTest 의 배선 테스트가 잡는다.
 *
 * Timer `mcp.tool.calls` + 태그 tool/outcome. outcome 3분류가 핵심 —
 * 우리 도구는 실패를 error 필드 JSON(isError:false)로 강등하는 관례(DAY 13/15)라서
 * 프로토콜 수준 성공/실패만 보면 실패율이 항상 0 으로 보인다. degraded 를 분리해 가시화한다:
 * - success: 정상 응답
 * - degraded: error 필드 응답 (호출은 성공, 내용이 실패를 알림)
 * - failure: 예외 전파 (관례 밖의 예상 밖 실패)
 */
@Component
class McpToolMetrics(private val registry: MeterRegistry) {

    private val mapper = JsonMapper.builder().build()

    fun <T> record(tool: String, call: () -> T): T {
        val sample = Timer.start(registry)
        try {
            val result = call()
            sample.stop(timer(tool, outcomeOf(result)))
            return result
        } catch (e: Throwable) {
            sample.stop(timer(tool, "failure"))
            throw e
        }
    }

    private fun outcomeOf(result: Any?): String {
        if (result !is String) return "success"
        return try {
            if (mapper.readTree(result).has("error")) "degraded" else "success"
        } catch (_: Exception) {
            "success" // JSON 이 아닌 응답은 강등 판정 대상이 아니다
        }
    }

    private fun timer(tool: String, outcome: String): Timer =
        Timer.builder("mcp.tool.calls")
            .description("MCP 도구 호출 횟수·응답 시간 (outcome: success/degraded/failure)")
            .tag("tool", tool)
            .tag("outcome", outcome)
            .register(registry)
}
