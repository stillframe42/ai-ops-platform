"""골든셋 스크립트 계약 — 원본은 시트 하나: 승격 시트의 기입란은 build_golden 이 읽을 수 있어야 하고, 재생성은 report·evidence 를
기존 jsonl 에서 승계하되 라벨은 시트가 결정해야 한다."""

import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from build_golden import build_row, read_labels, validate  # noqa: E402
from make_labeling_sheets import render  # noqa: E402
from promote_golden import fill_labels, promoted_note  # noqa: E402

REPORT = {
    "incident_id": "inc-error-rate-surge-20260913111041-b68558",
    "scenario": "error-rate-surge",
    "alert_name": "TargetAppHighErrorRate",
    "status": "completed",
    "completed_at": "2026-09-13T11:11:18Z",
    "analysis": {"severity": "P2", "root_cause_hypothesis": "상품 로직 결함", "evidence": ["5xx 42~53%"], "suggested_actions": ["담당자 확인"], "confidence": 0.93},
    "action": {"actions": ["ROLLBACK"], "rationale": "배포 결함 가능", "risk": "P2"},
}
EVALUATION = {
    "id": 2,
    "incident_id": REPORT["incident_id"],
    "judge_model": "gpt-5.6-terra",
    "prompt_version": "v2",
    "failure_mode": "B",
    "scores": {"faithfulness": {"score": 0.4}, "actionability": {"score": 0.4}, "severity_accuracy": {"score": 1.0}},
    "human_scores": {"faithfulness": 0.4, "actionability": 0.4, "severity_accuracy": 1.0},
    "human_failure_mode": "B",
    "review_note": "로그 미확인으로 단정 [labeled_by: claude, 사용자 검수 대기]",
    "reviewed_by": "claude (초안)",
}


def _promoted_sheet(tmp_path: Path) -> Path:
    sheet = fill_labels(render(REPORT, None), EVALUATION["human_scores"], EVALUATION["human_failure_mode"], promoted_note(EVALUATION))
    path = tmp_path / f"{REPORT['incident_id']}.md"
    path.write_text(sheet)
    return path


def test_promoted_sheet_labels_round_trip_and_draft_is_excluded(tmp_path: Path):
    path = _promoted_sheet(tmp_path)
    labels = read_labels(path)

    assert labels["faithfulness"] == "0.4" and labels["failure_mode"] == "B"
    assert "[승격 incident_evaluations id=2, Judge gpt-5.6-terra v2 F/A/S 0.4/0.4/1.0 B, reviewed_by claude (초안)]" in labels["note"]
    # 초안 표기는 시트에 그대로 남아 build_golden 이 제외한다 — 검수를 마친 사람이 표기를 바꾸면 편입된다
    with pytest.raises(ValueError, match="검수 대기"):
        validate(labels, path)


def test_rebuild_inherits_report_from_previous_row_but_labels_come_from_sheet(tmp_path: Path):
    path = _promoted_sheet(tmp_path)
    text = path.read_text().replace("사용자 검수 대기", "검수 완료 2026-09-14").replace("faithfulness: 0.4", "faithfulness: 0.7")
    path.write_text(text)
    previous = {
        "incident_id": REPORT["incident_id"],
        "scenario": "error-rate-surge",
        "report": {"analysis": {"severity": "P2"}},
        "evidence": {"metrics": [1]},
        "human_scores": {"faithfulness": 0.4, "actionability": 0.4, "severity_accuracy": 1.0},
        "failure_mode": "B",
        "note": "옛 라벨",
        "labeled_at": "2026-09-13",
    }

    row = build_row(path, tmp_path / "no-reports", {}, previous)

    assert row["report"] == previous["report"] and row["evidence"] == previous["evidence"]
    assert row["human_scores"]["faithfulness"] == 0.7 and row["labeled_at"] == "2026-09-14"
    assert "검수 완료 2026-09-14" in row["note"]


def test_rebuild_without_report_file_or_previous_row_fails_loudly(tmp_path: Path):
    path = _promoted_sheet(tmp_path)
    path.write_text(path.read_text().replace("사용자 검수 대기", "검수 완료 2026-09-14"))

    with pytest.raises(ValueError, match="원천이 없다"):
        build_row(path, tmp_path / "no-reports", {}, None)


def test_reports_dir_file_wins_over_previous_row(tmp_path: Path):
    path = _promoted_sheet(tmp_path)
    path.write_text(path.read_text().replace("사용자 검수 대기", "검수 완료 2026-09-14"))
    reports = tmp_path / "reports"
    reports.mkdir()
    (reports / f"{REPORT['incident_id']}.json").write_text(json.dumps(REPORT))

    row = build_row(path, reports, {}, {"incident_id": REPORT["incident_id"], "report": {"stale": True}, "evidence": None})

    assert row["report"]["analysis"]["root_cause_hypothesis"] == "상품 로직 결함"
    assert "confidence" not in row["report"]["analysis"]
    assert row["evidence"] is None and row["ground_truth"].startswith("POST /chaos/error-rate")
