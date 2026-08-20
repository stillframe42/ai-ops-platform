package stillframe42.llmgateway.api

/** OpenAI 오류 계약(400)으로 번역될 요청 검증 실패 — 컨트롤러 반환 타입을 성공 DTO 로 유지하기 위한 예외 경로 */
class InvalidRequestException(
    message: String,
    val param: String? = null,
) : RuntimeException(message)
