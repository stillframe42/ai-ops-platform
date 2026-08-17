package stillframe42.llmgateway.api

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/** OpenAI 오류 계약(400)으로 번역될 요청 검증 실패 — 컨트롤러 반환 타입을 성공 DTO 로 유지하기 위한 예외 경로 */
class InvalidRequestException(
    message: String,
    val param: String? = null,
) : RuntimeException(message)

@RestControllerAdvice
class GatewayExceptionHandler {

    @ExceptionHandler(InvalidRequestException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidRequest(e: InvalidRequestException): OpenAiError =
        OpenAiError.invalidRequest(e.message ?: "잘못된 요청", e.param)

    // relay 계층의 입력 검증 (예: 지원하지 않는 role) — 클라이언트 잘못이므로 500 이 아니라 400
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun illegalArgument(e: IllegalArgumentException): OpenAiError =
        OpenAiError.invalidRequest(e.message ?: "잘못된 요청")
}
