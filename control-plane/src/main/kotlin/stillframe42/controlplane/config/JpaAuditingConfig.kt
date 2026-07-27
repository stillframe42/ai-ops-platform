package stillframe42.controlplane.config

import java.time.Instant
import java.util.Optional
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.auditing.DateTimeProvider
import org.springframework.data.domain.AuditorAware
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

@Configuration
@EnableJpaAuditing(
    dateTimeProviderRef = "auditingDateTimeProvider",
    auditorAwareRef = "auditorProvider",
)
class JpaAuditingConfig {

    /** 기본 제공자는 LocalDateTime — 프로젝트 시점 표현(Instant, UTC)으로 직접 고정한다 */
    @Bean
    fun auditingDateTimeProvider(): DateTimeProvider = DateTimeProvider { Optional.of(Instant.now()) }

    /**
     * 현재 쓰기 주체는 결과 컨슈머(시스템 프로세스)뿐 — 'system' 고정.
     * 4주차 human-in-the-loop 승인에서 실제 주체(승인자)를 돌려주도록 교체된다 (ADR-0006 연결).
     */
    @Bean
    fun auditorProvider(): AuditorAware<String> = AuditorAware { Optional.of(SYSTEM_AUDITOR) }

    companion object {
        const val SYSTEM_AUDITOR = "system"
    }
}
