package stillframe42.controlplane.audit

import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * 감사 로그 (ADR-0016) — 누가·언제·무엇을 한 줄로 남긴다. 필드는 MDC 로 싣는다: docker 프로파일의 ECS JSON 이
 * MDC 를 최상위 필드로 내보내고 traceId 는 tracing 이 같은 경로로 채우므로 Loki 에서 `client_id`·`incident_id`·
 * `traceId` 축으로 질의된다. 로거명 `audit` 하나로 고정 — `logger.name="audit"` 이 감사 행 전체의 선택자.
 */
object AuditLog {

    const val LOGGER_NAME = "audit"

    private val logger = LoggerFactory.getLogger(LOGGER_NAME)

    fun record(type: String, fields: Map<String, String>, message: String) {
        val closeables = (mapOf("audit.type" to type) + fields).map { (k, v) -> MDC.putCloseable(k, v) }
        try {
            logger.info("{} {}", type, message)
        } finally {
            closeables.forEach { it.close() }
        }
    }
}
