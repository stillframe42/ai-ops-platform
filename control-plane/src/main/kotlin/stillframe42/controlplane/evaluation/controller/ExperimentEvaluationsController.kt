package stillframe42.controlplane.evaluation.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import stillframe42.controlplane.evaluation.service.IncidentEvaluationService
import tools.jackson.databind.json.JsonMapper

/**
 * A/B 실험 평가 목록 API — `GET /api/experiments/{name}/evaluations` (`ops:read`). 요약 API 는 평균만 주므로 부트스트랩 CI 처럼
 * 표본이 필요한 계산(evaluation-service/scripts/experiment_report.py)은 이 목록을 읽는다. 순서는 저장소의 최신 평가 순.
 */
@RestController
@RequestMapping("/api/experiments/{name}/evaluations")
class ExperimentEvaluationsController(private val incidentEvaluationService: IncidentEvaluationService) {

    private val mapper = JsonMapper.builder().build()

    @GetMapping
    fun list(@PathVariable name: String): List<IncidentEvaluationResponse> =
        incidentEvaluationService.findByExperimentName(name).map { detail ->
            IncidentEvaluationResponse.from(detail, mapper.readTree(detail.evaluation).path("scores"))
        }
}
