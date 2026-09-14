"""저품질 리뷰 → 골든셋 승격 (docs/quality-evaluation.md §8-6) — control-plane 리뷰 큐의 promoted 행을 **라벨링 시트**로 만든다.

jsonl 을 직접 쓰지 않는 이유: 골든셋의 원본은 시트(`golden/labeling/<incident_id>.md`) 하나고 `v1.jsonl` 은 `build_golden.py` 가
시트 전체에서 재생성하는 파생물이다. 승격 행이 시트 없이 jsonl 에만 있으면 재생성 때 사라지거나(전체 재생성) 원본 시트의
수정이 무시된다(부분 append) — 원본을 하나로 두면 이 간극이 없다.

흐름: `GET /api/evaluations/review-queue?status=promoted` → 각 건 `GET /api/incidents/{id}` → 보고서 원문·재조회 결과를
--reports-dir 에 저장(build_golden 입력) → `make_labeling_sheets.render` 로 1~4절 + 5절 기입란을 DB 의 사람 라벨로 채운 시트 저장.
- 5절 note = review_note 그대로 + 승격 꼬리표(`[승격 incident_evaluations id=…, Judge …, reviewed_by …]`). `사용자 검수 대기` 표기가
  있으면 build_golden 이 제외한다 — 검수를 마친 사람이 시트의 표기를 `[초안 …, 검수 완료 YYYY-MM-DD]` 로 바꾸고 재생성한다
- evidence 는 --prometheus-url/--loki-url 을 주면 온라인 경로와 같은 EvidenceCollector 로 재조회 (보존 밖이면 "보존 밖")
- 이미 시트가 있는 incident_id 는 건너뛴다 (사람이 기입란을 고쳤을 수 있다) — 덮어쓰려면 --force

인증: ops:read 스코프 토큰 — --token-file 또는 client credentials(--client-id + 환경변수 AUTH_CLIENT_SECRET_OPS_ADMIN).
사용: uv run python scripts/promote_golden.py --control-plane-url http://localhost:8081 --reports-dir <dir> \
        [--prometheus-url http://localhost:9091 --loki-url http://localhost:3100] [--dry-run]
      이어서: uv run python scripts/build_golden.py --labeling-dir golden/labeling --reports-dir <dir> --out golden/v1.jsonl
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from evaluation.evidence import EvidenceCollector, is_empty  # noqa: E402
from evaluation.tools.oauth_client import ClientCredentialsAuth  # noqa: E402

sys.path.insert(0, str(Path(__file__).parent))
from make_labeling_sheets import FILL_IN, GROUND_TRUTH, render  # noqa: E402

DEFAULT_CLIENT_ID = "ops-admin"
SECRET_ENV = "AUTH_CLIENT_SECRET_OPS_ADMIN"
QUEUE_LIMIT = 100
DIMENSIONS = ("faithfulness", "actionability", "severity_accuracy")


def build_client(args: argparse.Namespace) -> httpx.Client:
    if args.token_file:
        token = Path(args.token_file).read_text().strip()
        return httpx.Client(base_url=args.control_plane_url, headers={"Authorization": f"Bearer {token}"}, timeout=30.0)
    secret = os.environ.get(SECRET_ENV)
    if not secret:
        raise SystemExit(f"--token-file 또는 환경변수 {SECRET_ENV} 가 필요하다 (값은 infra/.env — 출력 금지)")
    auth = ClientCredentialsAuth(args.auth_token_url, args.client_id, secret, "ops:read")
    return httpx.Client(base_url=args.control_plane_url, auth=auth, timeout=30.0)


def judge_summary(evaluation: dict) -> str:
    scores = evaluation.get("scores") or {}
    values = "/".join(str((scores.get(d) or {}).get("score", "?")) for d in DIMENSIONS)
    return f"Judge {evaluation.get('judge_model')} {evaluation.get('prompt_version')} F/A/S {values} {evaluation.get('failure_mode')}"


def promoted_note(evaluation: dict) -> str:
    """사람 사유 + 승격 꼬리표 — 어느 DB 행에서 왔고 Judge 는 어떻게 봤는지를 시트에 남긴다 (대조용, 라벨 규약 밖 정보)."""
    note = (evaluation.get("review_note") or "").strip()
    # '#' 는 read_labels 가 주석으로 잘라내므로 id 표기에 쓰지 않는다
    audit = f"[승격 incident_evaluations id={evaluation['id']}, {judge_summary(evaluation)}, reviewed_by {evaluation.get('reviewed_by')}]"
    return f"{note} {audit}".strip()


def fill_labels(sheet: str, scores: dict[str, float], failure_mode: str, note: str) -> str:
    """빈 기입란(FILL_IN)을 사람 라벨로 채운다 — build_golden.read_labels 가 읽는 `key: value` 한 줄 형식."""
    if FILL_IN not in sheet:
        raise ValueError("기입란 블록을 찾을 수 없다 — make_labeling_sheets.FILL_IN 과 형식이 다르다")
    filled = "\n".join(
        [
            "```yaml",
            '# 앵커 4단계(1.0 / 0.7 / 0.4 / 0.0) 중 하나씩. 판단 기준 = "주입한 장애와 재조회 수치에 비춰 맞는가"',
            *[f"{dimension}: {scores[dimension]}" for dimension in DIMENSIONS],
            f"failure_mode: {failure_mode}",
            f"note: {note}",
            "```",
        ]
    )
    return sheet.replace(FILL_IN, filled)


async def collect_evidence(collector: EvidenceCollector | None, incident_id: str, completed_at: str | None) -> dict | None:
    if collector is None:
        return None
    try:
        return await collector.collect(incident_id, completed_at)
    except Exception as exc:  # noqa: BLE001 — 재조회 실패는 근거 없음 (온라인 경로와 같은 정책)
        print(f"재조회 실패 — 근거 없이 시트 생성 ({incident_id}): {exc!r}")
        return None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--control-plane-url", default="http://localhost:8081")
    parser.add_argument("--token-file", help="ops:read 토큰 파일 (미지정 시 client credentials)")
    parser.add_argument("--auth-token-url", default="http://localhost:8091/oauth2/token")
    parser.add_argument("--client-id", default=DEFAULT_CLIENT_ID)
    parser.add_argument("--reports-dir", required=True, type=Path, help="보고서 원문·재조회 결과 저장 위치 (build_golden 입력)")
    parser.add_argument("--labeling-dir", type=Path, default=Path("golden/labeling"))
    parser.add_argument("--prometheus-url")
    parser.add_argument("--loki-url")
    parser.add_argument("--ground-truth-override", type=Path, default=Path("golden/ground-truth-overrides.json"))
    parser.add_argument("--force", action="store_true", help="이미 있는 시트도 덮어쓴다 (사람이 고친 기입란이 지워진다)")
    parser.add_argument("--dry-run", action="store_true", help="파일에 쓰지 않고 대상만 출력")
    args = parser.parse_args()

    overrides: dict[str, str] = {}
    if args.ground_truth_override.exists():
        overrides = {k: v for k, v in json.loads(args.ground_truth_override.read_text()).items() if not k.startswith("_")}
    collector = EvidenceCollector(args.prometheus_url, args.loki_url) if args.prometheus_url and args.loki_url else None

    with build_client(args) as client:
        response = client.get("/api/evaluations/review-queue", params={"status": "promoted", "limit": QUEUE_LIMIT})
        response.raise_for_status()
        promoted = response.json()
        created, skipped = [], []
        for evaluation in promoted:
            incident_id = evaluation["incident_id"]
            target = args.labeling_dir / f"{incident_id}.md"
            if target.exists() and not args.force:
                skipped.append(f"{incident_id}: 시트 있음 — 라벨 보존 (덮어쓰려면 --force)")
                continue
            if not evaluation.get("human_scores") or not evaluation.get("human_failure_mode"):
                skipped.append(f"{incident_id}: 사람 점수·유형 없음 (promoted 인데 라벨 누락)")
                continue
            detail = client.get(f"/api/incidents/{incident_id}")
            if detail.status_code == 404:
                skipped.append(f"{incident_id}: 보고서 없음")
                continue
            detail.raise_for_status()
            report = detail.json()["report"]
            evidence = asyncio.run(collect_evidence(collector, incident_id, report.get("completed_at")))
            evidence = None if is_empty(evidence) else evidence
            sheet = fill_labels(
                render(report, evidence, overrides.get(incident_id) or GROUND_TRUTH.get(report.get("scenario"))),
                evaluation["human_scores"],
                evaluation["human_failure_mode"],
                promoted_note(evaluation),
            )
            created.append((incident_id, report, evidence, sheet))

    for reason in skipped:
        print(f"제외: {reason}")
    for incident_id, _, evidence, _ in created:
        print(f"시트: {incident_id} ({'재조회 포함' if evidence else '보존 밖'})")
    print(f"promoted {len(promoted)}건 → 시트 {len(created)}건, 제외 {len(skipped)}건")
    if args.dry_run or not created:
        return
    args.labeling_dir.mkdir(parents=True, exist_ok=True)
    args.reports_dir.mkdir(parents=True, exist_ok=True)
    for incident_id, report, evidence, sheet in created:
        (args.reports_dir / f"{incident_id}.json").write_text(json.dumps(report, ensure_ascii=False, indent=1))
        if evidence is not None:
            (args.reports_dir / f"{incident_id}.requery.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=1))
        (args.labeling_dir / f"{incident_id}.md").write_text(sheet)
    print(f"{args.labeling_dir}: 시트 {len(created)}건 생성 — 검수 뒤 build_golden.py 로 v1.jsonl 재생성")


if __name__ == "__main__":
    main()
