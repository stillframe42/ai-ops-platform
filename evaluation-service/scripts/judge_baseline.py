"""Judge 일관성 측정 — 골든셋(사람 라벨) × 반복 채점 → `golden/judge-baseline.json` 스냅샷 (docs/quality-evaluation.md §7).

온라인 경로와 같은 GatewayJudge(같은 프롬프트·같은 재조회 요약 문장)를 쓴다. 기본은 정답(주입 사실) 없이 채점 —
배포 형상 그대로 측정한다. `--with-ground-truth` 는 비교용.

지표: 차원별 MAE(|사람 − Judge| 평균) · Spearman ρ · 앵커 정확 일치율 · 케이스별 반복 표준편차 ·
      **false pass**(사람 0.4 이하 → Judge 0.7 이상, 관문 용도의 1차 기준) · 실패 유형 일치 · 정규화 발생 · 비용(토큰).
다운그레이드·폴백 응답(X-Gateway-Downgrade/Fallback)은 무효 표본 — 별도 집계해 baseline 에서 제외한다.

사용: uv run python scripts/judge_baseline.py --golden golden/v1.jsonl --repeat 3 --out golden/judge-baseline.json
      (환경: LLM_BASE_URL·AUTH_TOKEN_URL·AUTH_CLIENT_SECRET — 호스트에서 compose 게이트웨이 8090·auth-server 8091)
"""

from __future__ import annotations

import argparse
import asyncio
import json
import statistics
import sys
from datetime import UTC, datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from evaluation.config.settings import get_settings  # noqa: E402
from evaluation.judge import ANCHORS, DIMENSIONS, LOW_QUALITY_THRESHOLD  # noqa: E402
from evaluation.judge_gateway import GatewayJudge  # noqa: E402

DOWNGRADE_HEADERS = ("x-gateway-downgrade", "x-gateway-fallback")


def load_golden(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def report_of(row: dict) -> dict:
    """골든 행 → 컨슈머가 받는 보고서 형태 (row 의 식별 필드 + report 블록)."""
    return {
        "incident_id": row["incident_id"],
        "scenario": row["scenario"],
        "alert_name": row["alert_name"],
        **row["report"],
    }


async def score_all(
    settings, version: str, rows: list[dict], repeat: int, with_ground_truth: bool, concurrency: int
) -> list[dict]:
    """한 이벤트 루프 안에서 Judge 생성 → 채점 → 종료 — httpx 커넥션 풀은 루프에 묶인다."""
    judge = GatewayJudge(settings, prompt_version=version)
    semaphore = asyncio.Semaphore(concurrency)

    async def one(row: dict, rep: int) -> dict:
        async with semaphore:
            evaluation = await judge.evaluate(
                report_of(row), row.get("evidence"), ground_truth=row["ground_truth"] if with_ground_truth else None
            )
        result = {"incident_id": row["incident_id"], "rep": rep}
        if evaluation is None:
            result["ok"] = False
        else:
            result.update(
                ok=True,
                scores={d: evaluation.scores[d].score for d in DIMENSIONS},
                reasons={d: evaluation.scores[d].reason for d in DIMENSIONS},
                failure_mode=evaluation.failure_mode,
                judge_model=evaluation.judge_model,
                response_id=evaluation.judge_response_id,
            )
        print(f"{row['incident_id']:48s} rep{rep} {'ok ' + str(result.get('scores')) if result['ok'] else '실패'}", flush=True)
        return result

    try:
        return await asyncio.gather(*(one(row, rep) for row in rows for rep in range(repeat)))
    finally:
        await judge.aclose()


def spearman(xs: list[float], ys: list[float]) -> float | None:
    """동률 평균 순위 Spearman — scipy 없이. 분산 0 이면 None."""
    if len(xs) < 3:
        return None

    def ranks(values: list[float]) -> list[float]:
        order = sorted(range(len(values)), key=lambda i: values[i])
        result = [0.0] * len(values)
        i = 0
        while i < len(order):
            j = i
            while j + 1 < len(order) and values[order[j + 1]] == values[order[i]]:
                j += 1
            rank = (i + j) / 2 + 1
            for k in range(i, j + 1):
                result[order[k]] = rank
            i = j + 1
        return result

    rx, ry = ranks(xs), ranks(ys)
    mx, my = statistics.fmean(rx), statistics.fmean(ry)
    num = sum((a - mx) * (b - my) for a, b in zip(rx, ry))
    den = (sum((a - mx) ** 2 for a in rx) * sum((b - my) ** 2 for b in ry)) ** 0.5
    return None if den == 0 else round(num / den, 3)


def aggregate(rows: list[dict], results: list[dict]) -> dict:
    human = {r["incident_id"]: r for r in rows}
    ok = [r for r in results if r["ok"]]
    first = {r["incident_id"]: r for r in ok if r["rep"] == 0}
    summary: dict = {"cases": len(rows), "calls": len(results), "ok": len(ok), "failed": len(results) - len(ok)}

    per_dimension = {}
    for d in DIMENSIONS:
        pairs = [(human[i]["human_scores"][d], r["scores"][d]) for i, r in first.items()]
        if not pairs:
            continue
        diffs = [abs(h - j) for h, j in pairs]
        per_dimension[d] = {
            "mae": round(statistics.fmean(diffs), 3),
            "exact_match": round(sum(1 for x in diffs if x == 0) / len(diffs), 3),
            "spearman": spearman([h for h, _ in pairs], [j for _, j in pairs]),
            "false_pass": sum(1 for h, j in pairs if h <= 0.4 and j >= LOW_QUALITY_THRESHOLD),
            "false_fail": sum(1 for h, j in pairs if h >= LOW_QUALITY_THRESHOLD and j <= 0.4),
        }
    summary["per_dimension"] = per_dimension
    all_diffs = [abs(human[i]["human_scores"][d] - r["scores"][d]) for i, r in first.items() for d in DIMENSIONS]
    summary["overall"] = {
        "mae": round(statistics.fmean(all_diffs), 3) if all_diffs else None,
        "exact_match": round(sum(1 for x in all_diffs if x == 0) / len(all_diffs), 3) if all_diffs else None,
        "false_pass": sum(v["false_pass"] for v in per_dimension.values()),
        "false_fail": sum(v["false_fail"] for v in per_dimension.values()),
        # 케이스 단위 관문 판정 일치 — 사람 low_quality(어느 차원이든 < 0.7) vs Judge low_quality
        "gate_agreement": round(
            sum(
                1
                for i, r in first.items()
                if (min(human[i]["human_scores"].values()) < LOW_QUALITY_THRESHOLD) == (min(r["scores"].values()) < LOW_QUALITY_THRESHOLD)
            )
            / len(first),
            3,
        )
        if first
        else None,
        "failure_mode_match": round(sum(1 for i, r in first.items() if r["failure_mode"] == human[i]["failure_mode"]) / len(first), 3) if first else None,
    }

    # 반복 분산 — 케이스·차원별 표준편차 (반복 2회 이상일 때)
    by_case: dict[str, list[dict]] = {}
    for r in ok:
        by_case.setdefault(r["incident_id"], []).append(r)
    stdevs = {d: [] for d in DIMENSIONS}
    unstable = []
    for incident_id, rs in by_case.items():
        if len(rs) < 2:
            continue
        for d in DIMENSIONS:
            values = [r["scores"][d] for r in rs]
            sd = statistics.pstdev(values)
            stdevs[d].append(sd)
            if len(set(values)) > 1:
                unstable.append({"incident_id": incident_id, "dimension": d, "values": values})
    summary["repeat"] = {
        "reps": max((r["rep"] for r in results), default=0) + 1,
        "stdev_mean": {d: round(statistics.fmean(v), 3) for d, v in stdevs.items() if v},
        "unstable": unstable,
    }
    summary["judge_models"] = sorted({r["judge_model"] for r in ok})
    return summary


def case_table(rows: list[dict], results: list[dict]) -> list[dict]:
    table = []
    for row in rows:
        rs = sorted((r for r in results if r["incident_id"] == row["incident_id"] and r["ok"]), key=lambda r: r["rep"])
        table.append(
            {
                "incident_id": row["incident_id"],
                "scenario": row["scenario"],
                "human": {**row["human_scores"], "failure_mode": row["failure_mode"]},
                "judge": [{**r["scores"], "failure_mode": r["failure_mode"]} for r in rs],
                "judge_reasons": rs[0]["reasons"] if rs else None,
            }
        )
    return table


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--golden", type=Path, default=Path("golden/v1.jsonl"))
    parser.add_argument("--repeat", type=int, default=3)
    parser.add_argument("--concurrency", type=int, default=3)
    parser.add_argument("--with-ground-truth", action="store_true", help="주입 사실을 프롬프트에 포함 (비교용 — 온라인 경로에는 없다)")
    parser.add_argument("--prompt-version", default=None, help="미지정 = 설정(EVAL_JUDGE_PROMPT_VERSION)")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=None, help="앞 N 건만 (예산 확인용)")
    args = parser.parse_args()

    settings = get_settings()
    version = args.prompt_version or settings.eval_judge_prompt_version
    rows = load_golden(args.golden)[: args.limit]
    results = asyncio.run(score_all(settings, version, rows, args.repeat, args.with_ground_truth, args.concurrency))

    snapshot = {
        "measured_at": datetime.now(UTC).isoformat(timespec="seconds"),
        "golden": str(args.golden),
        "golden_cases": len(rows),
        "judge_prompt_version": version,
        "with_ground_truth": args.with_ground_truth,
        "gateway": settings.llm_base_url,
        "summary": aggregate(rows, results),
        "cases": case_table(rows, results),
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(snapshot, ensure_ascii=False, indent=1) + "\n")
    print(json.dumps(snapshot["summary"], ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
