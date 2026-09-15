package stillframe42.llmgateway.api

/**
 * 게이트웨이 커스텀 헤더 계약의 단일 원천 — README "헤더 계약" 표와 짝. 컨트롤러(설정)·감사 필터(판독)·
 * 예외 핸들러(차단 응답)가 같은 이름을 나눠 쓰므로 한 곳에 모아 드리프트를 막는다.
 * 표준 헤더(Retry-After·Authorization)는 여기 두지 않는다 — Spring `HttpHeaders` 상수를 쓴다.
 */
object GatewayHeaders {

    /** 요청 — 라우팅 정책 키 (monitoring-summary / root-cause-analysis / ...) */
    const val TASK_TYPE = "X-Task-Type"

    /** 요청 — `no-cache` 면 캐싱 제외 */
    const val CACHE_CONTROL = "X-Cache-Control"

    /** 요청 — 실험 variant `<experiment-name>:<variant>` (예: analysis-model-haiku:B). gateway.routing.experiments 에 정의된 것만 적용, 나머지는 무시 (ADR-0019) */
    const val EXPERIMENT_VARIANT = "X-Experiment-Variant"

    /** 응답 — 캐시 판정 (exact_hit / semantic_hit / miss / bypass) */
    const val CACHE = "X-Gateway-Cache"

    /** 응답 — 입력 가드레일 판정 (clean / suspect / flagged / blocked), 항상 존재 */
    const val GUARDRAIL = "X-Gateway-Guardrail"

    /** 응답 — 가드레일 판정 계층 (pattern / classifier / policy), 비클린일 때만 */
    const val GUARDRAIL_STAGE = "X-Gateway-Guardrail-Stage"

    /** 응답 — 예산 100% 도달로 저비용 모델 강제 전환됨 */
    const val DOWNGRADE = "X-Gateway-Downgrade"

    /** 응답 — 주 프로바이더 장애로 폴백 발생 (교차 프로바이더명 또는 local) */
    const val FALLBACK = "X-Gateway-Fallback"

    /** 응답 — 실제 적용된 실험 variant (요청 값 그대로), 적용됐을 때만 존재 */
    const val VARIANT = "X-Gateway-Variant"
}
