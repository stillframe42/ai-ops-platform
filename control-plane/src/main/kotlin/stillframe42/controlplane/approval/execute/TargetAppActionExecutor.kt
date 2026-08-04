package stillframe42.controlplane.approval.execute

import java.net.http.HttpClient
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import stillframe42.controlplane.approval.model.ActionExecution

/**
 * 화이트리스트 조치 실행기 (DAY 24, ADR-0005) — 자동 실행은 데모 안전 범위 1종뿐:
 * - CIRCUIT_BREAK: target-app /chaos/reset 호출 — 데모 매핑: 주입 해제로 5xx 확산 차단
 * - RESTART_APP: **자동 실행 제외 (2026-08-04 결정)** — 단일 인스턴스 재시작은 다운타임이
 *   실재하고 docker socket 권한 표면도 커서, 운영자 수동 조치 안내로 전환한다 (ADR-0005
 *   추가 사항). 회복 확인은 수동 조치 뒤의 Alert 해소를 그대로 관측한다.
 * 카탈로그의 나머지(SCALE_OUT·ROLLBACK)는 실행기가 없으므로 명시적 실패로 반환한다 —
 * 승인됐는데 조용히 아무 일도 일어나지 않는 경로를 만들지 않는다.
 */
@Component
class TargetAppActionExecutor(
    @Value("\${ops.action.target-app-base-url}") private val targetAppBaseUrl: String,
    @Value("\${ops.action.restart-container}") private val restartContainer: String,
) : ActionExecutor {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val restClient = RestClient.builder()
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
            ).apply { setReadTimeout(Duration.ofSeconds(5)) },
        )
        .build()

    override fun execute(action: String): ActionExecution = when (action) {
        CIRCUIT_BREAK -> circuitBreak()
        RESTART_APP -> restartGuidance()
        else -> ActionExecution(action, false, "미지원 조치 — 실행기 없음 (자동 실행: $CIRCUIT_BREAK)")
    }

    private fun circuitBreak(): ActionExecution = runCatching {
        restClient.post().uri("$targetAppBaseUrl/chaos/reset").retrieve().toBodilessEntity()
        ActionExecution(CIRCUIT_BREAK, true, "chaos/reset 호출 완료 — 주입 해제로 오류 확산 차단")
    }.getOrElse {
        logger.warn("CIRCUIT_BREAK 실행 실패 — {}", it.message)
        ActionExecution(CIRCUIT_BREAK, false, "chaos/reset 호출 실패: ${it.message}")
    }

    /** 재시작은 실행하지 않는다 — 안내만 만들어 스레드 회신·감사 기록에 싣는다 (수동 조치 전환) */
    private fun restartGuidance(): ActionExecution = ActionExecution(
        RESTART_APP,
        true,
        "운영자 직접 실행 대상 — `docker restart $restartContainer` (자동 재시작 제외, 2026-08-04 결정)",
        manual = true,
    )

    companion object {
        const val CIRCUIT_BREAK = "CIRCUIT_BREAK"
        const val RESTART_APP = "RESTART_APP"
    }
}
