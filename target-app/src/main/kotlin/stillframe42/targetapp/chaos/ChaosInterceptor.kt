package stillframe42.targetapp.chaos

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.concurrent.ThreadLocalRandom
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/**
 * 비즈니스 API 에 지연/에러를 주입한다. 핸들러 매핑 이후(preHandle)에 개입하므로
 * http_server_requests 메트릭의 uri 태그가 실제 패턴으로 기록된다 — 필터에서 주입하면 UNKNOWN 이 된다.
 */
@Component
class ChaosInterceptor(private val chaosState: ChaosState) : HandlerInterceptor {

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val latency = chaosState.latency
        if (latency != null && hit(latency.percent)) {
            Thread.sleep(latency.delayMs)
        }

        val errorRate = chaosState.errorRate
        if (errorRate != null && hit(errorRate.percent)) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "chaos: error-rate fault active")
            return false
        }
        return true
    }

    private fun hit(percent: Int): Boolean = ThreadLocalRandom.current().nextInt(100) < percent
}
