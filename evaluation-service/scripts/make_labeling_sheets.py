"""골든셋 라벨링 시트 생성기 (DAY 45) — 인시던트 보고서 + 시간창 재조회 결과 → 케이스당 마크다운 1파일.

입력: --reports-dir 에 `<incident_id>.json` (incident_reports.report 원문) 과
      `<incident_id>.requery.json` (Prometheus·Loki 재조회 결과, 없으면 "보존 밖" 표기)
출력: --out 디렉토리에 `<incident_id>.md`

시트에 넣지 않는 것: `analysis.confidence` (에이전트 자기 평가), Judge 점수 — 라벨링 앵커링 방지.
앵커 4단계·실패 유형은 docs/quality-evaluation.md §2 와 동일 문장을 쓴다.

사용: python scripts/make_labeling_sheets.py --reports-dir <dir> --out golden/labeling
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

# 시나리오별 표준 주입값 (infra/scripts/e2e-scenario.sh 기준 — 회차별 실제 값은 일일 파일 참조)
GROUND_TRUTH = {
    "error-rate-surge": "POST /chaos/error-rate?percent=50 — 요청의 50% 를 5xx 로 실패시킴",
    "latency-surge": "POST /chaos/latency?ms=3500&percent=100 — 전 요청에 3.5초 지연",
    "memory-pressure": "POST /chaos/memory-leak?mbPerMin=100 — 분당 100MB 힙 점유 (OOM 경로)",
}

ANCHORS = """\
| 앵커 | 뜻 |
|------|-----|
| 1.0 | 전부 맞음 — 근거·조치·등급이 실측과 일치 |
| 0.7 | 핵심은 맞고 사소한 부정확 |
| 0.4 | 절반쯤 맞음 — 핵심 주장 하나가 근거 없음 또는 조치 절반이 일반론 |
| 0.0 | 틀림 — 근거 없는 주장, 실행 불가 조치, 등급 오판 |

실패 유형 (가장 낮은 차원의 이유 하나): A 근거 없는 주장 / B 근거는 맞으나 결론 불일치 / C 조치 비구체·실행 불가 / D 심각도 오판 / 없음"""

FILL_IN = """\
```yaml
# 앵커 4단계(1.0 / 0.7 / 0.4 / 0.0) 중 하나씩. 판단 기준 = "주입한 장애와 재조회 수치에 비춰 맞는가"
faithfulness:
actionability:
severity_accuracy:
failure_mode:      # A | B | C | D | 없음
note:              # 한 줄 사유
```"""


def _ts_from_id(incident_id: str) -> str | None:
    """incident_id 의 발화 타임스탬프(YYYYMMDDHHMMSS) → ISO 문자열."""
    m = re.search(r"-(\d{14})-", incident_id)
    if not m:
        return None
    t = m.group(1)
    return f"{t[:4]}-{t[4:6]}-{t[6:8]}T{t[8:10]}:{t[10:12]}:{t[12:14]}Z"


def _bullets(items: list[str] | None) -> str:
    return "\n".join(f"- {x}" for x in items or []) or "- (없음)"


def _series_summary(series: list[dict]) -> str:
    """query_range 결과를 min/max/마지막 값으로 요약."""
    if not series:
        return "결과 없음 (시리즈 0)"
    rows = []
    for s in series:
        vals = [v[1] for v in s["values"]]
        label = ", ".join(f"{k}={v}" for k, v in s["metric"].items()) or "(합계)"
        rows.append(f"  - {label}: min {min(vals)} · max {max(vals)} · 마지막 {vals[-1]} ({len(vals)}점)")
    return "\n".join(rows)


def _requery_section(requery: dict | None) -> str:
    if requery is None:
        return "재조회 불가 — 보존 기간 밖 (Prometheus 10d · Loki 2026-08-28 이후). 보고서 내부 정합과 주입 사실만으로 판정한다."
    prom = requery["prometheus"]
    lines = [
        f"시간창: {requery['window'][0]} ~ {requery['window'][1]} (발화 5분 전 ~ 종결)",
        "",
        "Prometheus (30s step):",
        f"- 5xx 비율 `sum(rate 5xx) / sum(rate all)`:\n{_series_summary(prom['error_ratio'])}",
        f"- status 별 요청률:\n{_series_summary(prom['rate_by_status'])}",
        f"- p95 (초):\n{_series_summary(prom['p95_seconds'])}",
        f"- heap 사용 비율:\n{_series_summary(prom['heap_ratio'])}",
        "",
        f"Loki `{{service=\"target-app\"}}` ERROR {len(requery['loki']['ERROR'])}건 · WARN {len(requery['loki']['WARN'])}건 (창 안 50줄 상한)",
    ]
    for level in ("ERROR", "WARN"):
        for line in requery["loki"][level][:5]:
            lines.append(f"  - [{level}] {line[:200]}")
    return "\n".join(lines)


def render(report: dict, requery: dict | None, ground_truth: str | None = None) -> str:
    inc = report["incident_id"]
    scenario = report.get("scenario", "?")
    ground_truth = ground_truth or GROUND_TRUTH.get(scenario, "(알 수 없음)")
    analysis = report.get("analysis") or {}
    monitoring = report.get("monitoring") or {}
    action = report.get("action") or {}
    approval = report.get("approval") or {}
    recovery = report.get("recovery") or {}

    return f"""\
# 골든셋 라벨링 — {inc}

> 시나리오 **{scenario}** · Alert `{report.get('alert_name')}` · 발화 {_ts_from_id(inc) or '?'} · 종결 {report.get('completed_at')} · 상태 {report.get('status')}
> **주입 사실 (정답)**: {ground_truth}
> 판단 기준: 보고서가 그럴듯한가가 아니라 **주입 사실 + §4 재조회 수치에 맞는가**.

## 1. 모니터링 요약 (monitor 노드)

실행한 질의:
{_bullets(monitoring.get('evidences'))}

{monitoring.get('situation_summary', '(없음)')}

## 2. 분석 보고서 (analysis 노드) — 평가 대상

**severity**: {analysis.get('severity', '(없음)')}

**근본 원인 가설**:
{analysis.get('root_cause_hypothesis', '(없음)')}

**근거 (evidence)**:
{_bullets(analysis.get('evidence'))}

**제안 조치 (suggested_actions)**:
{_bullets(analysis.get('suggested_actions'))}

## 3. 조치 계획 (action 노드)

- actions: {', '.join(action.get('actions') or []) or '(없음)'}
- rationale: {action.get('rationale', '(없음)')}
- risk: {action.get('risk', '(없음)')}
- 승인: {approval.get('status', '(없음)')} · 회복: {recovery.get('status', '(없음)')}

## 4. 재조회 근거 (evaluation 이 독립 조회한 실측)

{_requery_section(requery)}

## 5. 기입란

{ANCHORS}

{FILL_IN}
"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--reports-dir", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument(
        "--ground-truth-override",
        type=Path,
        help="incident_id → 실제 정답 문장 JSON (합성 발화·레드팀 회차처럼 시나리오 표준 주입값이 맞지 않을 때)",
    )
    parser.add_argument("--force", action="store_true", help="이미 있는 시트도 덮어쓴다 (기입란이 지워지므로 기본은 건너뜀)")
    args = parser.parse_args()

    overrides: dict[str, str] = {}
    if args.ground_truth_override:
        overrides = {k: v for k, v in json.loads(args.ground_truth_override.read_text()).items() if not k.startswith("_")}

    args.out.mkdir(parents=True, exist_ok=True)
    count = 0
    for path in sorted(args.reports_dir.glob("*.json")):
        if path.name.endswith(".requery.json"):
            continue
        report = json.loads(path.read_text())
        requery_path = path.with_name(f"{path.stem}.requery.json")
        requery = json.loads(requery_path.read_text()) if requery_path.exists() else None
        # 재조회는 했으나 시리즈가 전부 비면 보존 밖으로 취급
        if requery and not any(requery["prometheus"].values()) and not any(requery["loki"].values()):
            requery = None
        target = args.out / f"{report['incident_id']}.md"
        if target.exists() and not args.force:
            print(f"{target} 건너뜀 (이미 있음 — 라벨 보존, 덮어쓰려면 --force)")
            continue
        target.write_text(render(report, requery, overrides.get(report["incident_id"])))
        count += 1
        print(f"{target} ({'재조회 포함' if requery else '보존 밖'})")
    print(f"{count}건 생성")


if __name__ == "__main__":
    main()
