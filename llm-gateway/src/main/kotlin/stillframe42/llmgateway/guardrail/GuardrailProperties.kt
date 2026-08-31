package stillframe42.llmgateway.guardrail

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 입력 가드레일 정책 — 패턴 1차(항상) + LLM 분류기 2차(의심 입력만).
 * 초기 정책은 플래깅 후 통과 — 차단(mode=block)은 오탐이 파이프라인을 세우므로 실측 뒤에 켠다.
 */
@ConfigurationProperties("gateway.guardrail")
data class GuardrailProperties(
    val enabled: Boolean = true,
    /** flag = 판정만 남기고 통과 / block = FLAGGED 를 400 으로 거부 */
    val mode: Mode = Mode.FLAG,
    val classifier: Classifier = Classifier(),
) {
    enum class Mode { FLAG, BLOCK }

    data class Classifier(
        val enabled: Boolean = true,
        /** 라우팅 규칙 키 — gateway.routing.rules 의 task 와 일치해야 저비용 모델로 간다 */
        val taskType: String = "guardrail-classify",
        /** 분류기에 보내는 의심 텍스트 상한 (문자) — 비용·레이턴시 상한 */
        val maxChars: Int = 3000,
    )
}
