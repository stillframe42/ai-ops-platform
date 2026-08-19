package stillframe42.llmgateway.cost

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 모델 단가 테이블 (weekly-plan Phase 4, 순서 검토 ⑫ — Sonnet 5 인트로 가격 8월 말 종료 대비 yml 외부화).
 * 대시보드 절감 비용 패널의 표준가 상수를 대체하는 단일 원천.
 */
@ConfigurationProperties("gateway.cost")
data class CostProperties(
    val prices: List<ModelPrice> = emptyList(),
) {
    data class ModelPrice(
        // 접두 매칭 — 프로바이더 응답 모델명은 날짜 접미가 붙는다 (claude-haiku-4-5-20251001, DAY 31 실측)
        val modelPrefix: String,
        // USD / 1M tokens
        val inputPerMtok: Double = 0.0,
        val outputPerMtok: Double = 0.0,
    )
}
