package stillframe42.controlplane.approval.execute

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 조치별 처리 방식 계약 — RESTART_APP 은 실행이 아니라 수동 안내(2026-08-04 결정),
 * 미지원 조치는 명시적 실패. CIRCUIT_BREAK 의 HTTP 실패도 예외 없이 반환값으로 강등된다.
 */
class TargetAppActionExecutorTest {

    private val executor = TargetAppActionExecutor(
        // 닫힌 포트 — CIRCUIT_BREAK 실패 경로 검증용 (성공 경로는 E2E 실측 몫)
        targetAppBaseUrl = "http://127.0.0.1:59999",
        restartContainer = "target-app",
    )

    @Test
    fun `RESTART_APP 은 실행하지 않고 수동 조치 안내를 반환한다`() {
        val result = executor.execute("RESTART_APP")

        assertTrue(result.manual)
        assertTrue(result.ok)
        assertTrue(result.detail.contains("docker restart target-app"), "명령 예시 포함: ${result.detail}")
    }

    @Test
    fun `미지원 조치는 명시적 실패로 반환한다 - 조용한 무시 금지`() {
        val result = executor.execute("SCALE_OUT")

        assertFalse(result.ok)
        assertFalse(result.manual)
        assertTrue(result.detail.contains("미지원"))
    }

    @Test
    fun `CIRCUIT_BREAK 호출 실패는 예외 없이 실패 결과로 반환한다`() {
        val result = executor.execute("CIRCUIT_BREAK")

        assertEquals("CIRCUIT_BREAK", result.action)
        assertFalse(result.ok)
        assertFalse(result.manual)
    }
}
