package stillframe42.controlplane.config

import java.util.concurrent.Executor
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.support.ContextPropagatingTaskDecorator
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * `@Async` 경계의 관측 컨텍스트 전파 (DAY 43) — 웹훅 수신·승인 API 요청의 trace 를 비동기 처리(ingestAsync·
 * ApprovalDecidedListener → Kafka 발행)로 잇는다. 이 설정이 없으면 Kafka 발행 observation 이 새 trace 로 시작해
 * "웹훅 → 그래프", "승인 → 재개" 가 각각 한 trace 로 묶이지 않는다 (2026-09-07 실측).
 *
 * @Async 전용 풀을 여기서 직접 만드는 이유: 프로젝트에 자체 Executor 빈(ActionExecutionConfig)이 있어 Boot 의
 * applicationTaskExecutor 자동 구성이 꺼져 있고(@ConditionalOnMissingBean(Executor)), 그 결과 @Async 는
 * SimpleAsyncTaskExecutor(스레드 무제한·데코레이터 없음)로 떨어져 있었다. ContextPropagatingTaskDecorator 가
 * 제출 시점의 observation(=현재 스팬·MDC)을 워커 스레드에 복원한다.
 * ActionExecutionConfig 의 전용 executor 에도 같은 데코레이터 — 승인 API → 조치 실행 → decisions 발행 → 재개가 한 trace.
 */
@Configuration
class AsyncContextPropagationConfig : AsyncConfigurer {

    override fun getAsyncExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        setThreadNamePrefix("async-")
        corePoolSize = 8
        maxPoolSize = 8
        setTaskDecorator(ContextPropagatingTaskDecorator())
        initialize()
    }
}
