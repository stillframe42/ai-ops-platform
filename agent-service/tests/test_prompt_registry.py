"""프롬프트 레지스트리 계약 — 버전 파일 로드·오버라이드·미존재 실패·캐시 키에 버전 포함."""

from pathlib import Path

import pytest

from app.agents import action_agent, analysis_agent, monitor_agent
from app.prompts.registry import AGENTS, PROMPT_ROOT, PromptRegistry, prompt_registry
from app.security.untrusted import UNTRUSTED_POLICY
from app.supervisor import router


def test_v1_files_exist_for_every_agent_and_are_used_as_system_prompts():
    registry = PromptRegistry("v1")
    for agent in AGENTS:
        assert (PROMPT_ROOT / agent / "v1.md").is_file()
        assert registry.get(agent).strip()
    # 에이전트 프롬프트 = 파일 + 정책 절 (정책은 파일에 없다 — 버전과 무관한 상수)
    assert monitor_agent.monitor_system_prompt("v1") == registry.get("monitor") + UNTRUSTED_POLICY
    assert analysis_agent.analysis_system_prompt("v1") == registry.get("analysis") + UNTRUSTED_POLICY
    assert action_agent.action_system_prompt("v1") == registry.get("action") + UNTRUSTED_POLICY
    assert router.route_prompt("v1") == registry.get("router") + UNTRUSTED_POLICY
    assert UNTRUSTED_POLICY not in registry.get("analysis")
    assert "{hypothesis}" in registry.get("router")  # 라우터 프롬프트의 format 자리 유지


def test_override_applies_per_agent_only():
    registry = PromptRegistry("v1", overrides={"analysis": "v2"})
    assert registry.version_of("analysis") == "v2"
    assert registry.version_of("monitor") == "v1"


def test_missing_version_or_agent_fails_fast(tmp_path: Path):
    (tmp_path / "analysis").mkdir()
    (tmp_path / "analysis" / "v1.md").write_text("v1 본문")
    registry = PromptRegistry("v1", root=tmp_path)
    assert registry.get("analysis") == "v1 본문"
    with pytest.raises(FileNotFoundError):
        registry.get("analysis", "v9")
    with pytest.raises(KeyError):
        registry.get("planner")


def test_settings_registry_reflects_prompt_version_env(monkeypatch):
    from app.config import get_settings

    monkeypatch.setenv("PROMPT_VERSION", "v1")
    monkeypatch.setenv("PROMPT_VERSION_OVERRIDES", '{"analysis": "v7"}')
    get_settings.cache_clear()
    try:
        registry = prompt_registry()
        assert registry.version_of("analysis") == "v7" and registry.version_of("action") == "v1"
        assert prompt_registry() is registry  # 같은 설정 = 같은 인스턴스
    finally:
        get_settings.cache_clear()


def test_agent_factories_cache_per_prompt_version(monkeypatch):
    """캐시 키에 버전이 들어간다 — 버전이 바뀌면 새 에이전트(새 시스템 프롬프트)를 만든다."""
    built: list[tuple[str, str]] = []

    def fake_create_agent(*, model, tools, system_prompt, **kwargs):
        built.append(("agent", system_prompt[:20]))
        return object()

    monkeypatch.setattr(monitor_agent, "create_agent", fake_create_agent)
    monkeypatch.setattr(monitor_agent, "create_llm", lambda settings, task_type=None: object())
    monitor_agent._build_monitor_agent.cache_clear()
    first = monitor_agent._build_monitor_agent("v1")
    assert monitor_agent._build_monitor_agent("v1") is first
    with pytest.raises(FileNotFoundError):
        monitor_agent._build_monitor_agent("v9")
    monitor_agent._build_monitor_agent.cache_clear()


def test_broken_analysis_prompt_exists_for_low_quality_induction():
    """저품질 유발용 분석 프롬프트 — 근거 인용·도구 검증 지시가 없어야 Judge Faithfulness 하락을 재현한다 (ADR-0019 확인 기준)."""
    registry = PromptRegistry("v1", overrides={"analysis": "v0-broken"})
    broken = registry.get("analysis")
    assert registry.version_of("analysis") == "v0-broken"
    assert "compare_with_baseline" not in broken and "get_app_logs" not in broken
    assert "근거" in registry.get("analysis", "v1")



def test_analysis_v2_changes_only_reasoning_structure():
    """실험 1 처리군 v2 — 단일 변인: 추론 구조(근거 인용 → 가설 → 반증 → 결론)만 바꾸고 severity 기준·환경 특성은 v1 과 동일."""
    registry = PromptRegistry("v1")
    v1, v2 = registry.get("analysis", "v1"), registry.get("analysis", "v2")

    def section(text: str, start: str, end: str | None) -> str:
        body = text.split(start, 1)[1]
        return body.split(end, 1)[0] if end else body

    assert section(v1, "severity 기준:", "confidence 는") == section(v2, "severity 기준:", "confidence 는")
    assert section(v1, "환경 특성 — 조치 판단 기준", None) == section(v2, "환경 특성 — 조치 판단 기준", None)
    assert "반증" in v2 and "반증" not in v1
