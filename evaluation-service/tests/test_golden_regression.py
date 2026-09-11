"""골든셋 회귀 — 실 Judge 로 20건을 1회 채점해 baseline(`golden/judge-baseline.json`) 대비 악화를 잡는다 (§7).

기본 제외(`-m 'not golden'`). 실행 조건: 게이트웨이·auth-server 접근 (LLM_BASE_URL·AUTH_TOKEN_URL·AUTH_CLIENT_SECRET).
실패 기준: 전체 MAE 가 baseline 보다 0.05 초과 악화 / false pass 가 baseline 보다 증가 / Judge 응답 실패.
반복 1회라 baseline(3회 평균)보다 분산이 크다 — 임계 0.05 는 반복 표준편차(≈0.05)와 같은 크기다.
"""

import asyncio
import json
import os
from pathlib import Path

import pytest

from evaluation.config.settings import Settings

pytestmark = pytest.mark.golden

GOLDEN = Path(__file__).resolve().parents[1] / "golden" / "v1.jsonl"
BASELINE = Path(__file__).resolve().parents[1] / "golden" / "judge-baseline.json"
MAE_TOLERANCE = 0.05


@pytest.fixture(scope="module")
def baseline() -> dict:
    if not BASELINE.is_file():
        pytest.skip("baseline 스냅샷 없음 — scripts/judge_baseline.py 로 먼저 만든다")
    return json.loads(BASELINE.read_text())


@pytest.fixture(scope="module")
def measurement(baseline: dict) -> dict:
    if not os.environ.get("AUTH_CLIENT_SECRET") or os.environ.get("AUTH_CLIENT_SECRET") == "test-secret":
        pytest.skip("게이트웨이 자격 없음 — AUTH_CLIENT_SECRET (evaluation-service 클라이언트)")
    from scripts.judge_baseline import aggregate, load_golden, score_all

    settings = Settings()
    rows = load_golden(GOLDEN)
    results = asyncio.run(
        score_all(settings, baseline["judge_prompt_version"], rows, repeat=1, with_ground_truth=baseline["with_ground_truth"], concurrency=3)
    )
    return aggregate(rows, results)


def test_all_judge_calls_succeed(measurement: dict):
    assert measurement["failed"] == 0, f"Judge 실패 {measurement['failed']}건"


def test_false_pass_does_not_grow(baseline: dict, measurement: dict):
    assert measurement["overall"]["false_pass"] <= baseline["summary"]["overall"]["false_pass"]


def test_mae_does_not_regress_beyond_tolerance(baseline: dict, measurement: dict):
    worse_by = measurement["overall"]["mae"] - baseline["summary"]["overall"]["mae"]
    assert worse_by <= MAE_TOLERANCE, f"MAE 악화 {worse_by:.3f} (baseline {baseline['summary']['overall']['mae']})"
