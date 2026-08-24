package stillframe42.llmgateway.fallback

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 프로바이더 폴백 체인 설정 — 주 프로바이더 장애 시 교차 프로바이더 재중계.
 * 폴백 모델은 라우팅 교차 검증 모델과 동일한 gpt-5.6-terra 단일 (신규 모델 도입 없음, 단가 등록 동반)
 */
@ConfigurationProperties("gateway.fallback")
data class FallbackProperties(
    val provider: String = "openai",
    val model: String = "gpt-5.6-terra",
    /** 원 라우트에 유효 max_tokens 가 없을 때의 폴백 기본값 */
    val maxTokens: Int = 2000,
    val circuit: Circuit = Circuit(),
) {
    /** 실패율 50% 초과 시 오픈, 30초 후 half-open */
    data class Circuit(
        val failureRateThreshold: Float = 50f,
        val waitInOpenSeconds: Long = 30,
        val slidingWindowSize: Int = 10,
        /** 실패율 판정 최소 표본 — 기본값(100)은 데모 트래픽에서 영원히 미달이라 하향 */
        val minimumCalls: Int = 4,
    )
}
