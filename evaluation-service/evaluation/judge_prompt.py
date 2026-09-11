"""Judge 프롬프트 — 버전 파일(`prompts/judge/<version>.md`) 로드 + 보고서·근거 → user 메시지 조립.

시스템 프롬프트를 파일로 두는 이유: 프롬프트 변경이 diff 로 보이고, `prompt_version` 이 페이로드·메트릭·baseline
스냅샷에 그대로 실려 "어느 프롬프트로 채점했나" 가 기록된다 (docs/quality-evaluation.md §7). 루브릭·앵커 문장은
§2 와 같아야 한다 — 사람(라벨링 시트)과 Judge 가 같은 기준을 읽는다.
"""

from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path

from evaluation.evidence import render_evidence
from evaluation.security.untrusted import UNTRUSTED_POLICY, wrap_untrusted

PROMPT_DIR = Path(__file__).parent / "prompts" / "judge"
DEFAULT_PROMPT_VERSION = "v1"


@lru_cache
def load_system_prompt(version: str = DEFAULT_PROMPT_VERSION) -> str:
    """버전 파일 + 비신뢰 콘텐츠 규칙. 없는 버전은 즉시 실패 — 오타가 조용히 다른 프롬프트로 채점되지 않게."""
    path = PROMPT_DIR / f"{version}.md"
    if not path.is_file():
        raise FileNotFoundError(f"Judge 프롬프트 버전 없음: {version} ({path})")
    return path.read_text(encoding="utf-8") + UNTRUSTED_POLICY


def evaluated_blocks(report: dict) -> dict:
    """평가 대상 블록 — analysis(confidence 제외) + action 계획 (§2). monitoring 은 질의 문자열뿐이라 제외."""
    analysis = report.get("analysis") or {}
    action = report.get("action") or {}
    return {
        "severity": analysis.get("severity"),
        "root_cause_hypothesis": analysis.get("root_cause_hypothesis"),
        "evidence": analysis.get("evidence"),
        "suggested_actions": analysis.get("suggested_actions"),
        "action_plan": {"actions": action.get("actions"), "rationale": action.get("rationale")},
    }


def build_user_prompt(report: dict, evidence: dict | None, ground_truth: str | None = None) -> str:
    """온라인 경로는 ground_truth 없음 — 재조회 근거만으로 판정한다. 골든셋 측정은 선택적으로 주입 사실을 준다."""
    sections = [
        f"## 인시던트\n{report.get('incident_id')} · 시나리오 {report.get('scenario')} · Alert {report.get('alert_name')}",
    ]
    if ground_truth:
        sections.append(f"## 주입 사실 (정답)\n{ground_truth}")
    sections.append(f"## 재조회 근거 (에이전트와 무관한 독립 실측)\n{render_evidence(evidence)}")
    report_text = json.dumps(evaluated_blocks(report), ensure_ascii=False, indent=1)
    sections.append(f"## 평가 대상 보고서\n{wrap_untrusted('incident-report', report_text)}")
    sections.append("위 기준으로 JSON 만 출력하라.")
    return "\n\n".join(sections)
