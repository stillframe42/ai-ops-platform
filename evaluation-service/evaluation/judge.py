"""Judge 인터페이스 — 보고서 + 재조회 근거 → 3차원 점수 (docs/quality-evaluation.md §2·§7).

구현(게이트웨이 `evaluation-judge` 호출·구조화 출력·프롬프트 버전)은 다음 단계 몫이다. 이 파일은 컨슈머가
의존하는 계약(입력·출력 형태·`ops.evaluation.results` 페이로드)만 고정한다 — 컨슈머·샘플링·근거 수집을 Judge 없이
먼저 실측하기 위해서다.
"""

import logging
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
    prompt_version: str
    evidence_available: bool
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
