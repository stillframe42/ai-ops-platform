package stillframe42.controlplane.incident.controller

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import stillframe42.controlplane.incident.service.IncidentReportService
import tools.jackson.databind.json.JsonMapper

/**
 * 인시던트 보고서 조회 API (DAY 19) — Slack 알림의 상세 링크가 이곳을 가리킨다.
 * 응답 형태는 IncidentReportResponse 의 data class 가 소유한다 (snake_case 와이어 고정).
 * 트랜잭션 경계는 서비스 몫 — 컨트롤러는 API 표현(limit 상한·404·직렬화)만 다룬다.
 */
@RestController
@RequestMapping("/api/incidents")
class IncidentQueryController(private val service: IncidentReportService) {

    private val mapper = JsonMapper.builder().build()

    @GetMapping
    fun list(@RequestParam(defaultValue = "20") limit: Int): List<IncidentSummaryResponse> =
        service.findRecent(limit.coerceIn(1, MAX_LIMIT)).map { IncidentSummaryResponse.from(it) }

    @GetMapping("/{incidentId}")
    fun detail(@PathVariable incidentId: String): IncidentDetailResponse {
        // null 반환은 404 가 아니라 빈 200 이 된다 — 미존재는 예외로 상태 코드를 분리
        val detail = service.findById(incidentId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "인시던트 없음: $incidentId")
        return IncidentDetailResponse(
            summary = IncidentSummaryResponse.from(detail.summary),
            // 저장된 jsonb 원문을 문자열이 아닌 JSON 객체로 내보낸다 — 클라이언트 이중 파싱 방지
            report = mapper.readTree(detail.report),
        )
    }

    companion object {
        private const val MAX_LIMIT = 100
    }
}
