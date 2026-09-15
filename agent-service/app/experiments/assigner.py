"""실험 배정 — incident_id 해시로 variant 를 결정한다 (ADR-0019 실험 층).

해시 결정론인 이유: 상태 저장 없이도 재실행·재생(replay)에서 같은 인시던트가 같은 variant 에 들어간다 (샘플링과 같은 방식).
실험 이름이 salt 라 실험이 여럿이어도 배정이 서로 얽히지 않는다.
"""

from __future__ import annotations

import hashlib
from functools import lru_cache
from pathlib import Path

from app.config import get_settings
from app.experiments.definition import ExperimentDefinition, load_experiments
from app.supervisor.state import ExperimentAssignment

_HASH_SPACE = 2**256


def _unit(name: str, incident_id: str) -> float:
    digest = hashlib.sha256(f"{name}:{incident_id}".encode()).hexdigest()
    return int(digest, 16) / _HASH_SPACE


class ExperimentAssigner:
    def __init__(self, definitions: list[ExperimentDefinition]) -> None:
        self._definitions = list(definitions)

    def assign(self, incident_id: str) -> ExperimentAssignment | None:
        """활성 실험 중 첫 번째 하나만 — 데모 표본이 동시 실험을 감당하지 못한다. sample 밖·비활성이면 None."""
        definition = next((d for d in self._definitions if d.active), None)
        if definition is None:
            return None
        unit = _unit(definition.name, incident_id)
        if unit >= definition.sample:
            return None
        # 편입 구간 [0, sample) 을 variant 수로 균등 분할 — 같은 해시값 하나로 편입과 variant 를 함께 정한다
        names = list(definition.variants)
        index = min(int(unit / definition.sample * len(names)), len(names) - 1)
        variant = names[index]
        spec = definition.variants[variant]
        return ExperimentAssignment(
            name=definition.name,
            variant=variant,
            prompt_version=spec.prompt,
            model_override=spec.model_override,
        )


@lru_cache
def _assigner(path: str) -> ExperimentAssigner:
    return ExperimentAssigner(load_experiments(Path(path)))


def experiment_assigner() -> ExperimentAssigner:
    """설정 기반 배정기 — 같은 파일이면 같은 인스턴스 (프로세스 수명 고정, 프롬프트 레지스트리와 같은 정책)."""
    return _assigner(get_settings().experiments_file)
