package stillframe42.controlplane.config

import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.support.ContextPropagatingTaskDecorator
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * `@Async` 전용 풀 + 관측 컨텍스트 전파 (DAY 43) — 웹훅 수신·승인 API 요청의 trace 를 비동기 처리(ingestAsync·
 * ApprovalDecidedListener → Kafka 발행)로 잇는다. 이 설정이 없으면 Kafka 발행 observation 이 새 trace 로 시작해
 * "웹훅 → 그래프", "승인 → 재개" 가 각각 한 trace 로 묶이지 않는다 (2026-09-07 실측).
 *
 * 풀을 여기서 직접 만드는 이유: 프로젝트에 자체 Executor 빈(ActionExecutionConfig)이 있어 Boot 의 applicationTaskExecutor
 * 자동 구성이 꺼져 있고(@ConditionalOnMissingBean(Executor)), 그 결과 @Async 는 SimpleAsyncTaskExecutor(스레드 무제한·
 * 데코레이터 없음)로 떨어져 있었다. ContextPropagatingTaskDecorator 가 제출 시점의 observation(=현재 스팬)을 워커에 복원한다.
 *
 * 큐 상한·거부 정책: 큐를 무제한으로 두면 Kafka 다운(ingest 가 max.block.ms 5초씩 대기) 중 웹훅이 202 로 계속 쌓여 메모리로
 * 간다. 상한 초과는 AbortPolicy 로 즉시 거부 — 웹훅 컨트롤러는 예외 → 5xx, Alertmanager 가 재전송한다(뒤로 미는 backpressure).
 * AFTER_COMMIT 리스너(Slack 카드·회신)의 거부는 예외 로그만 남고 카드가 유실된다 — 알려진 한계.
 * 빈으로 두어 Boot 가 executor.* 메트릭(queued/active/pool.size, name=asyncTaskExecutor)을 자동 노출한다.
 * ActionExecutionConfig 의 전용 executor 에도 같은 데코레이터 — 승인 API → 조치 실행 → decisions 발행 → 재개가 한 trace.
 */
@Configuration
class AsyncContextPropagationConfig : AsyncConfigurer {

    @Bean(ASYNC_EXECUTOR)
    fun asyncTaskExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        setThreadNamePrefix("async-")
        corePoolSize = 8
        maxPoolSize = 8
        queueCapacity = ASYNC_QUEUE_CAPACITY
        setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())
        setTaskDecorator(ContextPropagatingTaskDecorator())
        initialize()
    }

    override fun getAsyncExecutor(): Executor = asyncTaskExecutor()

    companion object {
        const val ASYNC_EXECUTOR = "asyncTaskExecutor"
        // 8 스레드 × 5초(max.block.ms) 로 초당 1.6건을 소화하므로 500 은 약 5분치 적체 — 그 이상은 재전송에 맡긴다
        const val ASYNC_QUEUE_CAPACITY = 500
    }
}
