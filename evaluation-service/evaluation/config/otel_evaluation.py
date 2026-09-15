"""평가 텔레메트리 — `gen_ai.evaluation.result` 이벤트 + `aiops.evaluation.*` 메트릭 (docs/quality-evaluation.md §6).

이벤트는 표준 어휘(semconv 0.65b0 `gen_ai.evaluation.*`)를 빌려 두 시그널로 낸다:
- 로그 레코드(event_name) — 규격의 이벤트 정의 그대로. Collector logs 파이프라인 → Loki
- 같은 속성의 스팬 이벤트 — Tempo trace 뷰에서 평가 스팬 안에 바로 보이게 (로그 시그널은 trace 화면 밖)
메트릭은 표준에 평가 히스토그램이 없어 자체 네임스페이스 — 앵커 경계(0.0/0.4/0.7/1.0)를 버킷으로 고정한다.
카운터 3종(판정·Judge 호출·샘플링)은 대시보드·SLO 룰이 Prometheus 만으로 실패 유형 분포·Judge 실패율·샘플링 커버리지를
그리기 위한 것 — 같은 사실이 스팬 속성에도 있지만 Tempo 는 알림 룰의 데이터소스가 아니다.
"""

from __future__ import annotations

from opentelemetry import metrics
from opentelemetry._logs import SeverityNumber, get_logger
from opentelemetry.trace import Span

from evaluation.config.otel_genai import CONVERSATION_ID
from evaluation.judge import LOW_QUALITY_THRESHOLD, Evaluation

EVENT_NAME = "gen_ai.evaluation.result"
ATTR_EVALUATION_NAME = "gen_ai.evaluation.name"
ATTR_SCORE_VALUE = "gen_ai.evaluation.score.value"
ATTR_SCORE_LABEL = "gen_ai.evaluation.score.label"
ATTR_EXPLANATION = "gen_ai.evaluation.explanation"
ATTR_RESPONSE_ID = "gen_ai.response.id"
LABEL_PASS, LABEL_FAIL = "pass", "fail"

METRIC_SCORE = "aiops.evaluation.score"
METRIC_VERDICTS = "aiops.evaluation.verdicts"  # 판정 1건 = 1 (failure_mode·severity·프롬프트 버전 축)
METRIC_JUDGE_CALLS = "aiops.evaluation.judge.calls"  # Judge 호출 1회 = 1 (outcome ok|error, error.type)
METRIC_SAMPLING = "aiops.evaluation.sampling"  # 소비 보고서 1건 = 1 (sampled·sampled_reason·profile)
# 앵커 경계 = 버킷 경계 — `le="0.4"` 이하가 저품질(< 0.7 은 le="0.4" 버킷 누적), 대시보드·SLO 룰의 축
SCORE_BUCKET_BOUNDARIES = (0.0, 0.4, 0.7, 1.0)

# 자체 확장 속성 (표준 네임스페이스에 임의 키를 넣지 않는다 — otel-genai-mapping.md §5)
ATTR_OPERATION = "aiops.operation"  # evaluate — gen_ai.operation.name 의 표준 값이 아니라 자체 키
ATTR_DIMENSION = "aiops.evaluation.dimension"
ATTR_SEVERITY = "aiops.evaluation.severity"  # 평가 대상 보고서의 P 등급 (에이전트 판정)
ATTR_PROMPT_VERSION = "aiops.prompt.version"  # 평가 대상 분석 프롬프트 버전 — 실험 축
ATTR_EXPERIMENT_NAME = "aiops.experiment.name"  # A/B 실험 좌표 (보고서 `experiment`, ADR-0019 결정 ③)
ATTR_EXPERIMENT_VARIANT = "aiops.experiment.variant"
ATTR_JUDGE_PROMPT_VERSION = "aiops.evaluation.judge_prompt_version"
ATTR_JUDGE_MODEL = "aiops.evaluation.judge_model"
ATTR_FAILURE_MODE = "aiops.evaluation.failure_mode"
ATTR_LOW_QUALITY = "aiops.evaluation.low_quality"
ATTR_NORMALIZED = "aiops.evaluation.normalized"
ATTR_LINK_REASON = "aiops.link.reason"
ATTR_JUDGE_OUTCOME = "aiops.evaluation.judge_outcome"  # ok | error
ATTR_ERROR_TYPE = "error.type"
ATTR_SAMPLED = "aiops.evaluation.sampled"
ATTR_SAMPLED_REASON = "aiops.evaluation.sampled_reason"
ATTR_SAMPLE_PROFILE = "aiops.evaluation.sample_profile"
OUTCOME_OK, OUTCOME_ERROR = "ok", "error"
LINK_REASON = "evaluation-of"
UNKNOWN_PROMPT_VERSION = "unknown"
NO_EXPERIMENT = "none"

_histogram: metrics.Histogram | None = None
_counters: dict[str, metrics.Counter] = {}


def score_histogram() -> metrics.Histogram:
    """지연 생성 — provider 등록(setup_telemetry) 뒤 첫 사용 시점에 계측기를 만든다."""
    global _histogram
    if _histogram is None:
        _histogram = metrics.get_meter("evaluation-service").create_histogram(
            METRIC_SCORE, unit="", description="LLM-as-a-Judge 차원별 점수 (앵커 1.0/0.7/0.4/0.0)"
        )
    return _histogram


def _counter(name: str, description: str) -> metrics.Counter:
    if name not in _counters:
        _counters[name] = metrics.get_meter("evaluation-service").create_counter(name, unit="", description=description)
    return _counters[name]


def record_judge_call(*, error_type: str | None) -> None:
    """Judge 호출 1회 — 성공은 outcome=ok, 실패는 outcome=error + error.type (`AiopsJudgeErrorRate` 룰의 분자·분모)."""
    attributes = {ATTR_JUDGE_OUTCOME: OUTCOME_ERROR if error_type else OUTCOME_OK}
    if error_type:
        attributes[ATTR_ERROR_TYPE] = error_type
    _counter(METRIC_JUDGE_CALLS, "Judge 게이트웨이 호출 수 (outcome ok|error)").add(1, attributes)


def record_sampling(*, sampled: bool, reason: str, profile: str) -> None:
    """소비 보고서 1건의 샘플링 결정 — 커버리지(sampled 비율)·사유 분포 패널의 축."""
    _counter(METRIC_SAMPLING, "소비 보고서 샘플링 결정 수").add(
        1, {ATTR_SAMPLED: str(sampled).lower(), ATTR_SAMPLED_REASON: reason, ATTR_SAMPLE_PROFILE: profile}
    )


def score_label(score: float) -> str:
    return LABEL_PASS if score >= LOW_QUALITY_THRESHOLD else LABEL_FAIL


def record_evaluation(evaluation: Evaluation, *, severity: str | None, span: Span) -> None:
    """평가 1건 → 차원별 이벤트 3건(로그 + 스팬 이벤트) + 히스토그램 3점. 현재 스팬 컨텍스트가 로그에 trace id 로 실린다."""
    logger = get_logger("evaluation-service")
    metric_attributes = {
        ATTR_SEVERITY: severity or "unknown",
        ATTR_PROMPT_VERSION: evaluation.analysis_prompt_version or UNKNOWN_PROMPT_VERSION,
        ATTR_JUDGE_PROMPT_VERSION: evaluation.prompt_version,
        # 실험 밖 보고서도 "none" 으로 라벨을 채운다 — Prometheus 에서 시리즈 형태를 고정하기 위해 (라벨 유무가 갈리면 sum by 가 두 갈래로 나뉜다)
        ATTR_EXPERIMENT_NAME: evaluation.experiment_name or NO_EXPERIMENT,
        ATTR_EXPERIMENT_VARIANT: evaluation.experiment_variant or NO_EXPERIMENT,
    }
    _counter(METRIC_VERDICTS, "Judge 판정 수 (failure_mode 축)").add(
        1, {ATTR_FAILURE_MODE: evaluation.failure_mode, ATTR_LOW_QUALITY: str(evaluation.low_quality).lower(), **metric_attributes}
    )
    for dimension, dimension_score in evaluation.scores.items():
        attributes = {
            ATTR_EVALUATION_NAME: dimension,
            ATTR_SCORE_VALUE: dimension_score.score,
            ATTR_SCORE_LABEL: score_label(dimension_score.score),
            ATTR_EXPLANATION: dimension_score.reason,
            CONVERSATION_ID: evaluation.incident_id,
            ATTR_JUDGE_PROMPT_VERSION: evaluation.prompt_version,
            ATTR_JUDGE_MODEL: evaluation.judge_model,
            ATTR_FAILURE_MODE: evaluation.failure_mode,
        }
        if evaluation.judge_response_id:
            attributes[ATTR_RESPONSE_ID] = evaluation.judge_response_id
        logger.emit(
            event_name=EVENT_NAME,
            severity_number=SeverityNumber.INFO,
            body=f"{dimension}={dimension_score.score} ({evaluation.incident_id})",
            attributes=attributes,
        )
        span.add_event(EVENT_NAME, attributes)
        score_histogram().record(dimension_score.score, {ATTR_DIMENSION: dimension, **metric_attributes})
