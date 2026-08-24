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
     * 감사 쓰기 주체는 시스템 프로세스뿐 — 'system' 고정.
     * 승인자 등 실제 주체는 도메인 컬럼(decided_by)이 별도 기록한다 (ADR-0006).
     */
    @Bean
    fun auditorProvider(): AuditorAware<String> = AuditorAware { Optional.of(SYSTEM_AUDITOR) }

    companion object {
        const val SYSTEM_AUDITOR = "system"
    }
}
