package stillframe42.controlplane.evaluation.controller

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import stillframe42.controlplane.evaluation.model.IncidentEvaluationDetail
import stillframe42.controlplane.evaluation.model.ReviewOutcome
import stillframe42.controlplane.evaluation.model.ReviewStatus
import stillframe42.controlplane.evaluation.service.IncidentEvaluationService
import tools.jackson.databind.json.JsonMapper

/**
 * 리뷰 큐 + 사람 검토 API — `GET /api/evaluations/review-queue`(ops:read) · `POST /api/evaluations/{id}/review`(ops:approve,
 * 사람의 결정이라 승인 API 와 같은 스코프). 승격 스크립트(evaluation-service `promote_golden.py`)는 큐를 status=promoted 로 읽는다.
 */
@RestController
@RequestMapping("/api/evaluations")
class EvaluationReviewController(private val incidentEvaluationService: IncidentEvaluationService) {

    private val mapper = JsonMapper.builder().build()

    @GetMapping("/review-queue")
    fun reviewQueue(
        @RequestParam(defaultValue = "pending_review") status: String,
        @RequestParam(defaultValue = "20") limit: Int,
    ): List<IncidentEvaluationResponse> {
        val reviewStatus = ReviewStatus.fromWireOrNull(status)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "모르는 status: $status")
        return incidentEvaluationService.findByReviewStatus(reviewStatus, limit.coerceIn(1, MAX_LIMIT)).map { toResponse(it) }
    }

    @PostMapping("/{id}/review")
    fun review(@PathVariable id: Long, @RequestBody body: ReviewRequest): IncidentEvaluationResponse {
        val review = try {
            body.toReview()
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }
        return when (val outcome = incidentEvaluationService.review(id, review)) {
            is ReviewOutcome.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND, "평가 없음: $id")
            is ReviewOutcome.AlreadyPromoted ->
                throw ResponseStatusException(HttpStatus.CONFLICT, "이미 골든셋으로 승격된 평가 — 라벨 변경은 골든셋 파일에서: $id")
            is ReviewOutcome.Reviewed -> toResponse(outcome.detail)
        }
    }

    private fun toResponse(detail: IncidentEvaluationDetail) =
        IncidentEvaluationResponse.from(detail, mapper.readTree(detail.evaluation).path("scores"))

    companion object {
        private const val MAX_LIMIT = 100
    }
}
