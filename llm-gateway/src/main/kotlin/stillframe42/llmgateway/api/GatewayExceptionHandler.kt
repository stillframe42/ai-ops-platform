package stillframe42.llmgateway.api

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import stillframe42.llmgateway.guardrail.GuardrailBlockedException

@RestControllerAdvice
class GatewayExceptionHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(InvalidRequestException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidRequest(e: InvalidRequestException): OpenAiError {
        logger.warn("요청 거부 (param={}): {}", e.param, e.message)
        return OpenAiError.invalidRequest(e.message ?: "잘못된 요청", e.param)
    }

    // mode=block 정책의 거부 — 판정 헤더는 200 응답과 같은 계약으로 실어 클라이언트·감사 로그가 한 축으로 본다
    @ExceptionHandler(GuardrailBlockedException::class)
    fun guardrailBlocked(e: GuardrailBlockedException): ResponseEntity<OpenAiError> {
        logger.warn("요청 거부 (guardrail): {}", e.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .header(GatewayHeaders.GUARDRAIL, e.decision.verdict.name.lowercase())
            .header(GatewayHeaders.GUARDRAIL_STAGE, e.decision.stage.name.lowercase())
            .body(OpenAiError(OpenAiError.Detail(message = e.message ?: "가드레일 거부", type = "invalid_request_error", code = "guardrail_blocked")))
    }

    // relay 계층의 입력 검증 (예: 지원하지 않는 role) — 클라이언트 잘못이므로 500 이 아니라 400
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun illegalArgument(e: IllegalArgumentException): OpenAiError {
        logger.warn("요청 거부: {}", e.message)
        return OpenAiError.invalidRequest(e.message ?: "잘못된 요청")
    }

    // 역직렬화 실패도 OpenAI 오류 계약으로 — Spring 기본 400 은 원인 무로그라 관측 사각 (DAY 31 실측)
    @ExceptionHandler(HttpMessageNotReadableException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun notReadable(e: HttpMessageNotReadableException): OpenAiError {
        logger.warn("요청 본문 해석 실패: {}", e.message)
        return OpenAiError.invalidRequest("요청 본문을 해석할 수 없습니다: ${e.message}")
    }
}
