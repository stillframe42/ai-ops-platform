"""Judge 계약 — 보고서 + 재조회 근거 → 3차원 점수 (docs/quality-evaluation.md §2·§7).

이 파일은 컨슈머가 의존하는 계약(입력·출력 형태·`ops.evaluation.results` 페이로드)과 Judge 출력의 정규화 규칙만
가진다. 게이트웨이 호출·텔레메트리는 `judge_gateway.py`, 프롬프트는 `judge_prompt.py`.
"""

import json
import logging
import re
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Protocol

logger = logging.getLogger(__name__)

DIMENSIONS = ("faithfulness", "actionability", "severity_accuracy")
ANCHORS = (1.0, 0.7, 0.4, 0.0)
FAILURE_MODES = ("A", "B", "C", "D", "없음")
# 차원 점수 < 0.7 = 저품질 (리뷰 큐 적재 기준, §2)
LOW_QUALITY_THRESHOLD = 0.7


@dataclass(frozen=True)
class DimensionScore:
    score: float  # 앵커 4단계 중 하나
    reason: str


@dataclass(frozen=True)
class Evaluation:
    """평가 1건 = `ops.evaluation.results` 메시지 1건."""

    incident_id: str
    scores: dict[str, DimensionScore]
    failure_mode: str
    judge_model: str
    prompt_version: str  # Judge 프롬프트 버전
    evidence_available: bool
    # 평가 대상 분석 프롬프트 버전 (보고서 `analysis.prompt_version`) — 실험 축. 구버전 보고서는 None
    analysis_prompt_version: str | None = None
    # Judge 응답 id (`gen_ai.response.id`) — 평가 이벤트와 Judge 호출 스팬의 상관 키, 페이로드에는 싣지 않는다
    judge_response_id: str | None = None
    evaluated_at: str = field(default_factory=lambda: datetime.now(UTC).isoformat())

    @property
    def low_quality(self) -> bool:
        return any(s.score < LOW_QUALITY_THRESHOLD for s in self.scores.values())

    def to_payload(self) -> dict:
        return {
            "incident_id": self.incident_id,
            "scores": {name: {"score": s.score, "reason": s.reason} for name, s in self.scores.items()},
            "failure_mode": self.failure_mode,
            "low_quality": self.low_quality,
            "judge_model": self.judge_model,
            "prompt_version": self.prompt_version,
            "analysis_prompt_version": self.analysis_prompt_version,
            "evidence_available": self.evidence_available,
            "evaluated_at": self.evaluated_at,
        }


class Judge(Protocol):
    async def evaluate(self, report: dict, evidence: dict | None) -> Evaluation | None:
        """None = 판정 없음 (구현 전·판정 불가) — 컨슈머는 발행 없이 정상 종료한다."""


class PendingJudge:
    """구현 전 자리 — 샘플링·근거 수집까지의 경로를 실측하기 위한 스텁. 호출 사실만 로그에 남긴다."""

    async def evaluate(self, report: dict, evidence: dict | None) -> Evaluation | None:
        logger.info(
            "Judge 미구현 — 판정 생략: %s (재조회 근거 %s)",
            report.get("incident_id"),
            "있음" if evidence else "없음",
        )
        return None


class VerdictError(ValueError):
    """Judge 응답을 판정으로 읽을 수 없다 — JSON 없음·차원 누락·점수 비수치. 호출 측은 판정 없음으로 처리한다."""


@dataclass(frozen=True)
class Verdict:
    scores: dict[str, DimensionScore]
    failure_mode: str
    # 앵커 스냅·실패 유형 보정이 일어났으면 True — 프롬프트 준수율 관측용
    normalized: bool = False


# 실패 유형을 Judge 가 규칙과 어긋나게 냈을 때의 보정 — 가장 낮은 차원 → 그 차원의 대표 유형 (§2 표)
_DEFAULT_MODE_BY_DIMENSION = {"faithfulness": "A", "actionability": "C", "severity_accuracy": "D"}


def snap_to_anchor(score: float) -> float:
    return min(ANCHORS, key=lambda anchor: abs(anchor - score))


def parse_verdict(text: str) -> Verdict:
    """응답 본문 → Verdict. JSON 하나를 꺼내고(코드 펜스·앞뒤 문장 허용) 앵커·실패 유형 규칙으로 정규화한다.

    정규화 규칙 (§2): 점수는 네 앵커 중 하나 — 연속값이 오면 가장 가까운 앵커로 스냅. failure_mode 는 어떤 차원이든
    0.4 이하일 때만 유효하고 전부 0.7 이상이면 '없음' — Judge 가 어긋나게 내면 규칙 쪽으로 맞춘다 (사람 라벨과
    같은 규칙으로 비교해야 MAE·일치율이 의미를 가진다).
    """
    match = re.search(r"\{.*\}", text, flags=re.S)
    if not match:
        raise VerdictError(f"JSON 없음: {text[:200]!r}")
    try:
        data = json.loads(match.group(0))
    except json.JSONDecodeError as exc:
        raise VerdictError(f"JSON 파싱 실패: {exc}") from exc
    if not isinstance(data, dict):
        raise VerdictError("JSON 객체가 아님")

    normalized = False
    scores: dict[str, DimensionScore] = {}
    for dimension in DIMENSIONS:
        entry = data.get(dimension)
        if not isinstance(entry, dict) or "score" not in entry:
            raise VerdictError(f"차원 누락: {dimension}")
        try:
            raw = float(entry["score"])
        except (TypeError, ValueError) as exc:
            raise VerdictError(f"점수 비수치: {dimension}={entry['score']!r}") from exc
        score = snap_to_anchor(min(max(raw, 0.0), 1.0))
        normalized |= score != raw
        scores[dimension] = DimensionScore(score, str(entry.get("reason") or "").strip())

    failure_mode = str(data.get("failure_mode") or "없음").strip()
    if failure_mode not in FAILURE_MODES:
        failure_mode, normalized = "없음", True
    lowest_dimension = min(DIMENSIONS, key=lambda d: scores[d].score)
    if scores[lowest_dimension].score < LOW_QUALITY_THRESHOLD:
        if failure_mode == "없음":
            failure_mode, normalized = _DEFAULT_MODE_BY_DIMENSION[lowest_dimension], True
    elif failure_mode != "없음":
        failure_mode, normalized = "없음", True
    return Verdict(scores=scores, failure_mode=failure_mode, normalized=normalized)
