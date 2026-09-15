package stillframe42.controlplane.evaluation.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import stillframe42.controlplane.evaluation.service.IncidentEvaluationService

/**
 * A/B 실험 요약 API (ADR-0019 결정 ③) — `GET /api/experiments/{name}/summary` (평가 조회 계열과 같은 `ops:read` 보호 범위).
 * 평가가 없는 실험은 빈 variants — 실험이 시작 전이거나 이름이 틀린 경우를 구분할 수 없고 "평가 없음" 은 정상 상태라 404 가 아니다.
 */
@RestController
@RequestMapping("/api/experiments/{name}/summary")
class ExperimentSummaryController(private val incidentEvaluationService: IncidentEvaluationService) {

    @GetMapping
    fun summary(@PathVariable name: String): ExperimentSummaryResponse =
        ExperimentSummaryResponse.from(incidentEvaluationService.summarizeExperiment(name))
}
