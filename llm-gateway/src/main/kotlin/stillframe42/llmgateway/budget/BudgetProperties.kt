package stillframe42.llmgateway.budget

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 일별 예산 통제 (weekly-plan Phase 4) — daily-limit-usd 부재 = 비활성 (키-게이트 관례).
 * 100% 도달은 차단이 아니라 저비용 모델 강제 다운그레이드 — 장애 대응 파이프라인을 멈추지 않는다.
 */
@ConfigurationProperties("gateway.budget")
data class BudgetProperties(
    val dailyLimitUsd: Double? = null,
    // 서비스별 일 한도 (X-Client-Service 헤더 기준) — 미등록 서비스는 전체 한도만 적용
    val serviceDailyLimitUsd: Map<String, Double> = emptyMap(),
    // 경고 발송 임계 비율 (전체 한도 기준)
    val warnRatio: Double = 0.8,
    val downgrade: Downgrade = Downgrade(),
) {
    data class Downgrade(
        val provider: String = "anthropic",
        val model: String = "claude-haiku-4-5",
        val maxTokens: Int? = 2000,
    )
}
