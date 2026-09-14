"""골든셋 조립 (DAY 46) — 라벨링 시트(§5 기입란) 전체를 읽어 `golden/v1.jsonl` 을 **재생성**한다 (docs/quality-evaluation.md §8-4).

원본은 시트 하나다: 사람 라벨은 항상 시트에서, jsonl 은 매번 전부 다시 만든다 (부분 append 없음 — 원본 시트를 고친 뒤 재생성해도
승격 행이 사라지거나 수정이 무시되는 일이 없게). 승격 케이스도 `promote_golden.py` 가 시트로 만들어 같은 경로를 탄다.

규칙:
- 검수 완료 표기(`[초안 …, 검수 완료 YYYY-MM-DD]`)가 있거나 사람이 직접 기입한 시트만 편입 — `사용자 검수 대기` 는 제외
- 세 차원 점수는 앵커 4단계(1.0/0.7/0.4/0.0)만 허용, failure_mode 는 A~D·없음
- 한 행 = 케이스 1건: incident_id·scenario·alert_name·severity(에이전트 판정)·ground_truth·report(평가 대상 블록)·
  evidence(재조회 원본, 없으면 null)·human_scores·failure_mode·note·labeled_at
- report·evidence 의 원천은 --reports-dir 의 `<id>.json`·`<id>.requery.json`. 파일이 없으면 **기존 jsonl 의 같은 행에서 승계** —
  보존 기간이 지난 케이스는 다시 조회할 수 없으므로 앞선 산출물이 유일한 사본이다 (라벨은 그래도 시트가 결정)

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


def build_row(sheet: Path, reports_dir: Path, overrides: dict[str, str], previous: dict | None = None) -> dict:
    """시트 1장 → 행 1개. 라벨은 시트가, report·evidence 는 reports-dir 파일이 — 파일이 없으면 previous(기존 jsonl 행)에서 승계."""
    inc = sheet.stem
    scores, mode, note = validate(read_labels(sheet), sheet)
    reviewed = REVIEWED_MARK.search(note)
    labels = {
        "human_scores": scores,
        "failure_mode": mode,
        "note": note,
        "labeled_at": reviewed.group(1) if reviewed else datetime.now(UTC).strftime("%Y-%m-%d"),
    }
    report_path = reports_dir / f"{inc}.json"
    if not report_path.exists():
        if previous is None:
            raise ValueError(f"{sheet.name}: 보고서 파일({report_path})도 기존 jsonl 행도 없다 — report·evidence 를 채울 원천이 없다")
        return {**previous, **labels}
    report = json.loads(report_path.read_text())
    requery_path = reports_dir / f"{inc}.requery.json"
    evidence = json.loads(requery_path.read_text()) if requery_path.exists() else None
    if is_empty(evidence):
        evidence = None
    return {
        "incident_id": inc,
        "scenario": report.get("scenario"),
        "alert_name": report.get("alert_name"),
        "severity": (report.get("analysis") or {}).get("severity"),
        "ground_truth": overrides.get(inc) or GROUND_TRUTH.get(report.get("scenario"), "(알 수 없음)"),
        "report": report_block(report),
        "evidence": evidence,
        **labels,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--labeling-dir", required=True, type=Path)
    parser.add_argument("--reports-dir", required=True, type=Path)
    parser.add_argument("--ground-truth-override", type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()

    overrides: dict[str, str] = {}
    if args.ground_truth_override:
        overrides = {k: v for k, v in json.loads(args.ground_truth_override.read_text()).items() if not k.startswith("_")}

    # 기존 산출물은 report·evidence 승계용으로만 읽는다 — 라벨은 시트가 결정하고, 시트가 없는 행은 살아남지 않는다
    previous: dict[str, dict] = {}
    if args.out.exists():
        for line in args.out.read_text().splitlines():
            if line.strip():
                row = json.loads(line)
                previous[row["incident_id"]] = row

    rows, skipped = [], []
    for sheet in sorted(args.labeling_dir.glob("inc-*.md")):
        try:
            rows.append(build_row(sheet, args.reports_dir, overrides, previous.get(sheet.stem)))
        except ValueError as exc:
            skipped.append(str(exc))
    for reason in skipped:
        print(f"제외: {reason}")
    dropped = sorted(set(previous) - {r["incident_id"] for r in rows} - {s.split(":")[0] for s in skipped})
    for inc in dropped:
        print(f"삭제: {inc} — 시트 없음 (jsonl 에만 있던 행은 유지하지 않는다)")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows))
    by_scenario: dict[str, int] = {}
    for r in rows:
        by_scenario[r["scenario"]] = by_scenario.get(r["scenario"], 0) + 1
    new = len([r for r in rows if r["incident_id"] not in previous])
    print(f"{args.out}: {len(rows)}건 (신규 {new}, 기존 {len(rows) - new}) — " + ", ".join(f"{k} {v}" for k, v in sorted(by_scenario.items())))


if __name__ == "__main__":
    main()
