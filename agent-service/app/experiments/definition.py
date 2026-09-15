"""실험 정의 — `experiments.yml` 을 읽어 검증한다. 배정 규칙은 assigner 몫."""

from __future__ import annotations

from pathlib import Path

import yaml
from pydantic import BaseModel, Field, model_validator

EXPERIMENTS_FILE = Path(__file__).parent / "experiments.yml"
# 실험 대상 노드 — 배정 결과를 해석하는 코드가 있는 노드만 (다른 노드는 배정을 무시하므로 정의 자체를 막는다)
TARGETS = ("analysis",)
STATUSES = ("active", "concluded")


class VariantSpec(BaseModel):
    prompt: str | None = None  # 프롬프트 레지스트리 버전 — None 이면 설정 기본
    model_override: bool = False  # True 면 게이트웨이가 (실험명, variant) 정의로 모델을 바꾼다


class ExperimentDefinition(BaseModel):
    name: str
    target: str
    status: str = "active"
    sample: float = Field(default=1.0, ge=0.0, le=1.0)
    variants: dict[str, VariantSpec]

    @model_validator(mode="after")
    def _validate(self) -> ExperimentDefinition:
        if self.target not in TARGETS:
            raise ValueError(f"실험 {self.name}: 대상 노드 {self.target!r} 는 배정을 해석하지 않는다 (허용 {TARGETS})")
        if self.status not in STATUSES:
            raise ValueError(f"실험 {self.name}: status {self.status!r} (허용 {STATUSES})")
        if len(self.variants) < 2:
            raise ValueError(f"실험 {self.name}: variant 가 2개 이상이어야 비교가 된다")
        return self

    @property
    def active(self) -> bool:
        return self.status == "active"

    @property
    def control(self) -> str:
        return next(iter(self.variants))


def load_experiments(path: Path = EXPERIMENTS_FILE) -> list[ExperimentDefinition]:
    """없는 파일·잘못된 정의는 즉시 실패 — 실험이 조용히 빠진 채 운영되지 않게 (레지스트리와 같은 정책)."""
    raw = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    definitions = [ExperimentDefinition.model_validate(item) for item in raw.get("experiments") or []]
    names = [d.name for d in definitions]
    if len(names) != len(set(names)):
        raise ValueError(f"실험 이름 중복: {names}")
    return definitions
