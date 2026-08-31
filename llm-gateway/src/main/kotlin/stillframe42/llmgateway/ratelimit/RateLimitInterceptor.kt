package stillframe42.llmgateway.ratelimit

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.servlet.HandlerInterceptor
import stillframe42.llmgateway.api.OpenAiError
import stillframe42.llmgateway.relay.GatewayMetrics
import stillframe42.llmgateway.security.ClientIdentity
import tools.jackson.databind.ObjectMapper

/**
 * v1 전 경로 진입 지점의 분당 한도 검사 — 초과 시 429 + Retry-After (OpenAI 오류 계약,
 * 클라이언트 SDK 의 표준 재시도 로직이 그대로 동작하도록).
 */
class RateLimitInterceptor(
    private val rateLimiter: RateLimiter,
    private val gatewayMetrics: GatewayMetrics,
    private val objectMapper: ObjectMapper,
) : HandlerInterceptor {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val service = ClientIdentity.current()
        val decision = rateLimiter.tryConsume(service)
        if (decision.allowed) return true

        logger.warn("분당 한도 초과 — service={}, retryAfter={}s", service, decision.retryAfterSeconds)
        gatewayMetrics.rateLimited(service)
        response.status = 429
        response.setHeader(HttpHeaders.RETRY_AFTER, decision.retryAfterSeconds.toString())
        // charset 명시 필수 — Tomcat writer 는 미지정 시 ISO-8859-1 이라 한글 메시지가 깨진다 (8/19 실측)
        response.characterEncoding = Charsets.UTF_8.name()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write(
            objectMapper.writeValueAsString(
                OpenAiError.rateLimited("분당 요청 한도를 초과했습니다 (service=$service) — ${decision.retryAfterSeconds}초 후 재시도"),
            ),
        )
        return false
    }
}
