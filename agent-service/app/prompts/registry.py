"""프롬프트 레지스트리 — 에이전트별 시스템 프롬프트를 버전 파일(`app/prompts/{agent}/{version}.md`)에서 읽는다.

모듈 상수였던 프롬프트를 파일로 옮긴 이유: 버전이 페이로드(`analysis.prompt_version`)·스팬(`aiops.prompt.version`)·
평가 메트릭에 실려 "어느 프롬프트가 만든 보고서인가" 를 되짚을 수 있고, 프롬프트 v1 vs v2 실험이 코드 배포 없이
설정(`PROMPT_VERSION`·`PROMPT_VERSION_OVERRIDES`)으로 가능하다 (ADR-0019 실험 층).
파일 내용은 비신뢰 정책 절(`UNTRUSTED_POLICY`)을 담지 않는다 — 에이전트가 붙인다 (정책은 버전과 무관한 상수).
"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from app.config import get_settings

PROMPT_ROOT = Path(__file__).parent
AGENTS = ("monitor", "analysis", "action", "router")


class PromptRegistry:
    def __init__(self, default_version: str, overrides: dict[str, str] | None = None, root: Path = PROMPT_ROOT) -> None:
        self._default_version = default_version
        self._overrides = dict(overrides or {})
        self._root = root
        self._cache: dict[tuple[str, str], str] = {}

    def version_of(self, agent: str) -> str:
        """에이전트별 오버라이드 > 전역 기본 — 실험은 보통 한 에이전트(분석)만 바꾼다."""
        return self._overrides.get(agent, self._default_version)

    def get(self, agent: str, version: str | None = None) -> str:
        """프롬프트 본문. 없는 에이전트·버전은 즉시 실패 — 오타가 조용히 다른 프롬프트로 실행되지 않게."""
        if agent not in AGENTS:
            raise KeyError(f"모르는 에이전트: {agent}")
        resolved = version or self.version_of(agent)
        key = (agent, resolved)
        if key not in self._cache:
            path = self._root / agent / f"{resolved}.md"
            if not path.is_file():
                raise FileNotFoundError(f"프롬프트 버전 없음: {agent}/{resolved} ({path})")
            self._cache[key] = path.read_text(encoding="utf-8")
        return self._cache[key]


@lru_cache
def _registry(default_version: str, overrides: tuple[tuple[str, str], ...]) -> PromptRegistry:
    return PromptRegistry(default_version, dict(overrides))


def prompt_registry() -> PromptRegistry:
    """설정 기반 레지스트리 — 같은 설정이면 같은 인스턴스 (파일 캐시 공유)."""
    settings = get_settings()
    return _registry(settings.prompt_version, tuple(sorted(settings.prompt_version_overrides.items())))
