package stillframe42.controlplane.evaluation.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import stillframe42.controlplane.evaluation.service.IncidentEvaluationService
import tools.jackson.databind.json.JsonMapper

/**
 * 인시던트 평가 조회 API — `GET /api/incidents/{id}/evaluations` (인시던트 조회 계열과 같은 `ops:read` 보호 범위).
 * 평가가 없는 인시던트는 빈 목록 — 보고서 존재 여부와 무관하게 "평가 없음" 은 정상 상태라 404 가 아니다.
 */
@RestController
@RequestMapping("/api/incidents/{incidentId}/evaluations")
class IncidentEvaluationController(private val incidentEvaluationService: IncidentEvaluationService) {

    private val mapper = JsonMapper.builder().build()

    @GetMapping
    fun list(@PathVariable incidentId: String): List<IncidentEvaluationResponse> =
        incidentEvaluationService.findByIncidentId(incidentId).map { detail ->
            // 원문 jsonb 의 scores(차원별 score·reason)를 문자열 재파싱 없이 객체로 — report 와 같은 이유
            IncidentEvaluationResponse.from(detail, mapper.readTree(detail.evaluation).path("scores"))
        }
}
