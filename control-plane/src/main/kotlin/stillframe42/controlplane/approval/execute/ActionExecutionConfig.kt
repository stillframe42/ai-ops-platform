package stillframe42.controlplane.approval.execute

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * 조치 실행 전용 스레드풀 (DAY 24) — 재시작은 수 초~수십 초라 기본 @Async 풀
 * (alert 수신·Slack 발송 공용)과 분리한다: Alert 폭풍 때 실행 대기가 다른 비동기
 * 작업을 지연시키지 않도록 (application.yml max.block.ms 주석과 같은 계열의 격벽).
 */
@Configuration
class ActionExecutionConfig {

    @Bean(ACTION_EXECUTION_EXECUTOR)
    fun actionExecutionExecutor(): TaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 2
        queueCapacity = 8
        setThreadNamePrefix("action-exec-")
        initialize()
    }

    companion object {
        const val ACTION_EXECUTION_EXECUTOR = "actionExecutionExecutor"
    }
}
