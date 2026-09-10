"""골든셋 라벨링 시트 생성기 (DAY 45) — 인시던트 보고서 + 시간창 재조회 결과 → 케이스당 마크다운 1파일.

입력: --reports-dir 에 `<incident_id>.json` (incident_reports.report 원문) 과
      `<incident_id>.requery.json` (Prometheus·Loki 재조회 결과). 재조회 파일이 없고 --prometheus-url/--loki-url 을
      주면 evaluation/evidence.py 의 EvidenceCollector 로 직접 재조회해 저장한다 (DAY 46 — 온라인 경로와 같은 질의·창)
출력: --out 디렉토리에 `<incident_id>.md`

시트에 넣지 않는 것: `analysis.confidence` (에이전트 자기 평가), Judge 점수 — 라벨링 앵커링 방지.
앵커 4단계·실패 유형은 docs/quality-evaluation.md §2 와 동일 문장을 쓴다.

사용: python scripts/make_labeling_sheets.py --reports-dir <dir> --out golden/labeling \
        [--prometheus-url http://127.0.0.1:19090 --loki-url http://127.0.0.1:13100]
"""

from __future__ import annotations

import argparse
import asyncio
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from evaluation.evidence import EvidenceCollector, is_empty, render_evidence  # noqa: E402

# 시나리오별 표준 주입값 (infra/scripts/e2e-scenario.sh 기준 — 회차별 실제 값은 ground-truth-overrides.json)
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

# judge_preview.py 가 같은 이름으로 import 한다 — 온라인 경로(evaluation/evidence.py)의 문장을 그대로 쓴다
_requery_section = render_evidence


def _ts_from_id(incident_id: str) -> str | None:
    """incident_id 의 발화 타임스탬프(YYYYMMDDHHMMSS) → ISO 문자열."""
    m = re.search(r"-(\d{14})-", incident_id)
    if not m:
        return None
    t = m.group(1)
    return f"{t[:4]}-{t[4:6]}-{t[6:8]}T{t[8:10]}:{t[10:12]}:{t[12:14]}Z"


def _bullets(items: list[str] | None) -> str:
    return "\n".join(f"- {x}" for x in items or []) or "- (없음)"


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

{render_evidence(requery)}

## 5. 기입란

{ANCHORS}

{FILL_IN}
"""


def load_requery(reports_dir: Path, report: dict, collector: EvidenceCollector | None) -> dict | None:
    """`<id>.requery.json` 이 있으면 읽고, 없고 collector 가 있으면 재조회해 저장한다. 전부 비면 None (보존 밖)."""
    path = reports_dir / f"{report['incident_id']}.requery.json"
    if path.exists():
        requery = json.loads(path.read_text())
    elif collector is not None:
        requery = asyncio.run(collector.collect(report["incident_id"], report.get("completed_at")))
        path.write_text(json.dumps(requery, ensure_ascii=False, indent=1))
    else:
        return None
    return None if is_empty(requery) else requery


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--reports-dir", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument(
        "--ground-truth-override",
        type=Path,
        help="incident_id → 실제 정답 문장 JSON (합성 발화·레드팀 회차처럼 시나리오 표준 주입값이 맞지 않을 때)",
    )
    parser.add_argument("--prometheus-url", help="재조회 파일이 없을 때 직접 재조회 (loki-url 과 함께)")
    parser.add_argument("--loki-url")
    parser.add_argument("--force", action="store_true", help="이미 있는 시트도 덮어쓴다 (기입란이 지워지므로 기본은 건너뜀)")
    args = parser.parse_args()

    overrides: dict[str, str] = {}
    if args.ground_truth_override:
        overrides = {k: v for k, v in json.loads(args.ground_truth_override.read_text()).items() if not k.startswith("_")}
    collector = EvidenceCollector(args.prometheus_url, args.loki_url) if args.prometheus_url and args.loki_url else None

    args.out.mkdir(parents=True, exist_ok=True)
    count = 0
    for path in sorted(args.reports_dir.glob("*.json")):
        if path.name.endswith(".requery.json"):
            continue
        report = json.loads(path.read_text())
        if report.get("status") != "completed":
            # partial 은 분석 블록이 없거나 불완전 — 평가 대상이 아니다 (샘플링도 skipped, docs §3)
            print(f"{report['incident_id']} 건너뜀 (status={report.get('status')} — 평가 대상 아님)")
            continue
        target = args.out / f"{report['incident_id']}.md"
        if target.exists() and not args.force:
            print(f"{target} 건너뜀 (이미 있음 — 라벨 보존, 덮어쓰려면 --force)")
            continue
        requery = load_requery(args.reports_dir, report, collector)
        target.write_text(render(report, requery, overrides.get(report["incident_id"])))
        count += 1
        print(f"{target} ({'재조회 포함' if requery else '보존 밖'})")
    print(f"{count}건 생성")


if __name__ == "__main__":
    main()
