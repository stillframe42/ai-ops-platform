"""골든셋 조립 (DAY 46) — 라벨링 시트(§5 기입란)를 읽어 `golden/v1.jsonl` 로 만든다 (docs/quality-evaluation.md §8-4).

규칙:
- 검수 완료 표기(`[초안 …, 검수 완료 YYYY-MM-DD]`)가 있거나 사람이 직접 기입한 시트만 편입 — `사용자 검수 대기` 는 제외
- 세 차원 점수는 앵커 4단계(1.0/0.7/0.4/0.0)만 허용, failure_mode 는 A~D·없음
- 한 행 = 케이스 1건: incident_id·scenario·alert_name·severity(에이전트 판정)·ground_truth·report(평가 대상 블록)·
  evidence(재조회 원본, 없으면 null)·human_scores·failure_mode·note·labeled_at
- 승격된 저품질 케이스도 같은 형식으로 이어 붙인다 (append)

사용: python scripts/build_golden.py --labeling-dir golden/labeling --reports-dir <dir> \
        --ground-truth-override golden/ground-truth-overrides.json --out golden/v1.jsonl
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import UTC, datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from evaluation.evidence import is_empty  # noqa: E402
from evaluation.judge import ANCHORS, DIMENSIONS, FAILURE_MODES  # noqa: E402

sys.path.insert(0, str(Path(__file__).parent))
from make_labeling_sheets import GROUND_TRUTH  # noqa: E402

PENDING_MARK = "사용자 검수 대기"
REVIEWED_MARK = re.compile(r"검수 완료 (\d{4}-\d{2}-\d{2})")


def read_labels(sheet: Path) -> dict:
    """§5 yaml 기입란 — `key: value` 한 줄씩 (주석 제거). 값이 비면 None."""
    text = sheet.read_text()
    m = re.search(r"```yaml\n(.*?)```", text, flags=re.S)
    if not m:
        raise ValueError(f"기입란 없음: {sheet}")
    labels: dict[str, str | None] = {}
    for line in m.group(1).splitlines():
        line = line.split("#", 1)[0].rstrip() if not line.lstrip().startswith("#") else ""
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        labels[key.strip()] = value.strip() or None
    return labels


def validate(labels: dict, sheet: Path) -> tuple[dict[str, float], str, str]:
    scores: dict[str, float] = {}
    for dim in DIMENSIONS:
        raw = labels.get(dim)
        if raw is None:
            raise ValueError(f"{sheet.name}: {dim} 미기입")
        score = float(raw)
        if score not in ANCHORS:
            raise ValueError(f"{sheet.name}: {dim}={score} 는 앵커 {ANCHORS} 밖")
        scores[dim] = score
    mode = labels.get("failure_mode")
    if mode not in FAILURE_MODES:
        raise ValueError(f"{sheet.name}: failure_mode={mode!r} 는 {FAILURE_MODES} 밖")
    note = labels.get("note") or ""
    if PENDING_MARK in note:
        raise ValueError(f"{sheet.name}: 검수 대기 시트는 골든셋에 넣지 않는다")
    if any(s <= 0.4 for s in scores.values()) and mode == "없음":
        raise ValueError(f"{sheet.name}: 0.4 이하 차원이 있으면 failure_mode 가 필요하다")
    if all(s >= 0.7 for s in scores.values()) and mode != "없음":
        raise ValueError(f"{sheet.name}: 전부 0.7 이상이면 failure_mode 는 없음")
    return scores, mode, note


def report_block(report: dict) -> dict:
    """평가 대상 블록만 — confidence 는 제외 (§2)."""
    analysis = dict(report.get("analysis") or {})
    analysis.pop("confidence", None)
    action = report.get("action") or {}
    return {
        "analysis": analysis,
        "action": {"actions": action.get("actions"), "rationale": action.get("rationale"), "risk": action.get("risk")},
        "approval_status": (report.get("approval") or {}).get("status"),
        "recovery_status": (report.get("recovery") or {}).get("status"),
        "status": report.get("status"),
        "completed_at": report.get("completed_at"),
    }


def build_row(sheet: Path, reports_dir: Path, overrides: dict[str, str]) -> dict:
    inc = sheet.stem
    report = json.loads((reports_dir / f"{inc}.json").read_text())
    requery_path = reports_dir / f"{inc}.requery.json"
    evidence = json.loads(requery_path.read_text()) if requery_path.exists() else None
    if is_empty(evidence):
        evidence = None
    scores, mode, note = validate(read_labels(sheet), sheet)
    reviewed = REVIEWED_MARK.search(note)
    return {
        "incident_id": inc,
        "scenario": report.get("scenario"),
        "alert_name": report.get("alert_name"),
        "severity": (report.get("analysis") or {}).get("severity"),
        "ground_truth": overrides.get(inc) or GROUND_TRUTH.get(report.get("scenario"), "(알 수 없음)"),
        "report": report_block(report),
        "evidence": evidence,
        "human_scores": scores,
        "failure_mode": mode,
        "note": note,
        "labeled_at": reviewed.group(1) if reviewed else datetime.now(UTC).strftime("%Y-%m-%d"),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--labeling-dir", required=True, type=Path)
    parser.add_argument("--reports-dir", required=True, type=Path)
    parser.add_argument("--ground-truth-override", type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--append", action="store_true", help="기존 파일에 없는 케이스만 이어 붙인다 (승격 케이스)")
    args = parser.parse_args()

    overrides: dict[str, str] = {}
    if args.ground_truth_override:
        overrides = {k: v for k, v in json.loads(args.ground_truth_override.read_text()).items() if not k.startswith("_")}

    existing: dict[str, dict] = {}
    if args.append and args.out.exists():
        for line in args.out.read_text().splitlines():
            if line.strip():
                row = json.loads(line)
                existing[row["incident_id"]] = row

    rows, skipped = [], []
    for sheet in sorted(args.labeling_dir.glob("inc-*.md")):
        if sheet.stem in existing:
            continue
        try:
            rows.append(build_row(sheet, args.reports_dir, overrides))
        except ValueError as exc:
            skipped.append(str(exc))
    for reason in skipped:
        print(f"제외: {reason}")

    all_rows = list(existing.values()) + rows
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in all_rows))
    by_scenario: dict[str, int] = {}
    for r in all_rows:
        by_scenario[r["scenario"]] = by_scenario.get(r["scenario"], 0) + 1
    print(f"{args.out}: {len(all_rows)}건 (신규 {len(rows)}) — " + ", ".join(f"{k} {v}" for k, v in sorted(by_scenario.items())))


if __name__ == "__main__":
    main()
