"""A/B 실험 결과 리포트 (ADR-0019 결정 ③ — 사전 정의 승자 기준) — variant 별 Judge 점수·비용·처리 시간을 세 원천에서 모아 판정한다.

원천 3 (control-plane DB 에는 Judge 결과만 있다 — 2026-09-15 결정):
- Judge 3차원·저품질: `GET /api/experiments/{name}/evaluations` (ops:read) — 인시던트당 최신 평가 1건
- 처리 시간·토큰: Tempo — 인시던트의 `invoke_agent analysis` 스팬 지속 시간 + 그 자식 `chat` 스팬의 `gen_ai.usage.*` 합
  (실험 변인이 분석 노드라 워크플로 전체가 아니라 분석 노드만 잰다. 승인 대기 시간은 사람 몫이라 제외)
- 비용: 토큰 × 단가 — 게이트웨이 원장에는 incident 축이 없어 spans 의 토큰에 gateway.yml `gateway.cost.prices` 와 같은 단가를 곱한다
  (`--prices` JSON 으로 교체 가능). 캐시 적중은 chat 스팬 토큰이 0 이 아니어도 게이트웨이 비용은 0 이지만 실험 경로는 bypass 라 차이 없음

판정 (사전 기준, 실험 설계 시 고정): 처리군 Faithfulness 평균 − control ≥ +0.05 이고 부트스트랩 95% CI 가 0 을 포함하지 않으며
비용 증가가 +30% 이내면 처리군 승자. CI 가 0 을 포함하거나 차이가 +0.05 미만이면 "보류". 처리군이 유의하게 낮거나 비용 초과면 control 유지.
부트스트랩은 B=2000·seed 고정 — 같은 표본에 같은 CI 가 나와야 리포트를 재현할 수 있다.

사용: uv run python scripts/experiment_report.py --experiment analysis-prompt-v2 --since 2026-09-16T02:32:00Z \
        [--control-plane-url http://localhost:8081 --tempo-url http://localhost:3200] [--token-file <f>] [--out report.md] [--samples-out samples.json]
인증: ops:read 토큰 — --token-file 또는 client credentials(--client-id + 환경변수 AUTH_CLIENT_SECRET_OPS_ADMIN, 값 출력 금지).
실험 2(재생): --experiment analysis-model-haiku --control-from analysis-prompt-v2:A — 처리군은 `<원본>-replay-B` 행, control 은 실험 1 의 A(v1·sonnet)
  원본 행을 같은 인시던트로 짝지어 쓴다 (원본이 B 면 프롬프트까지 달라 제외). 재생 한계(도구가 "지금" 조회)는 리포트에 적는다.
Tempo 보존(compose 48h) 밖의 인시던트는 처리 시간·비용이 비고 Judge 점수만 표에 남는다.
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from statistics import mean

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from evaluation.tools.oauth_client import ClientCredentialsAuth  # noqa: E402

DEFAULT_CLIENT_ID = "ops-admin"
SECRET_ENV = "AUTH_CLIENT_SECRET_OPS_ADMIN"
ANALYSIS_SPAN = "invoke_agent analysis"
CHAT_SPAN_PREFIX = "chat "
AGENT_SERVICE = "agent-service"
BOOTSTRAP_ITERATIONS = 2000
BOOTSTRAP_SEED = 20260916
MIN_FAITHFULNESS_GAIN = 0.05
MAX_COST_INCREASE = 0.30
# gateway.yml gateway.cost.prices 와 같은 값 (USD / 1M tokens, 모델명 접두 매칭) — 바뀌면 --prices 로 넘긴다
DEFAULT_PRICES: dict[str, tuple[float, float]] = {
    "claude-sonnet-5": (3.0, 15.0),
    "claude-haiku-4-5": (1.0, 5.0),
    "gpt-5.6-terra": (2.0, 12.0),
}


@dataclass(frozen=True)
class AnalysisMetrics:
    duration_s: float
    llm_calls: int
    input_tokens: int
    output_tokens: int
    cost_usd: float


@dataclass(frozen=True)
class Sample:
    incident_id: str
    variant: str
    faithfulness: float
    actionability: float
    severity_accuracy: float
    low_quality: bool
    partial: bool
    metrics: AnalysisMetrics | None


@dataclass(frozen=True)
class VariantStats:
    variant: str
    n: int
    faithfulness: float
    actionability: float
    severity_accuracy: float
    low_quality_rate: float
    duration_s: float | None
    input_tokens: float | None
    output_tokens: float | None
    cost_usd: float | None
    partial_rate: float


@dataclass(frozen=True)
class Verdict:
    winner: str | None
    label: str
    reason: str


def bootstrap_diff_ci(treatment: list[float], control: list[float], iterations: int = BOOTSTRAP_ITERATIONS, seed: int = BOOTSTRAP_SEED) -> tuple[float, float]:
    """평균 차이(처리군 − control)의 퍼센타일 부트스트랩 95% CI — 표본이 한 자리라 정규 근사 대신 재표집."""
    rng = random.Random(seed)
    diffs = sorted(
        mean(rng.choices(treatment, k=len(treatment))) - mean(rng.choices(control, k=len(control)))
        for _ in range(iterations)
    )
    return diffs[int(0.025 * iterations)], diffs[min(int(0.975 * iterations), iterations - 1)]


def decide(control: VariantStats, treatment: VariantStats, faithfulness_ci: tuple[float, float]) -> Verdict:
    gain = treatment.faithfulness - control.faithfulness
    cost_increase = None
    if control.cost_usd and treatment.cost_usd is not None:
        cost_increase = treatment.cost_usd / control.cost_usd - 1.0
    ci_excludes_zero = faithfulness_ci[0] > 0 or faithfulness_ci[1] < 0
    gain_text = f"Faithfulness 차이 {gain:+.2f} (95% CI {faithfulness_ci[0]:+.2f} ~ {faithfulness_ci[1]:+.2f})"
    cost_text = f"비용 {cost_increase:+.0%}" if cost_increase is not None else "비용 비교 불가"

    if not ci_excludes_zero:
        return Verdict(None, "보류", f"{gain_text} — CI 가 0 을 포함해 차이를 말할 수 없다. 표본을 늘려 재판정")
    if gain < 0:
        return Verdict(control.variant, f"{control.variant} 유지", f"{gain_text} — 처리군이 유의하게 낮다")
    if gain < MIN_FAITHFULNESS_GAIN:
        return Verdict(None, "보류", f"{gain_text} — 유의하지만 사전 기준 +{MIN_FAITHFULNESS_GAIN:.2f} 미만")
    if cost_increase is not None and cost_increase > MAX_COST_INCREASE:
        return Verdict(control.variant, f"{control.variant} 유지 (비용)", f"{gain_text}, {cost_text} — 비용 증가가 사전 한도 +{MAX_COST_INCREASE:.0%} 초과")
    return Verdict(treatment.variant, f"{treatment.variant} 승자", f"{gain_text}, {cost_text} — 사전 기준 충족")


def _attributes(span: dict) -> dict[str, str]:
    result = {}
    for attribute in span.get("attributes", []):
        value = attribute.get("value", {})
        result[attribute["key"]] = str(next(iter(value.values()), ""))
    return result


def _price(model: str, prices: dict[str, tuple[float, float]]) -> tuple[float, float]:
    for prefix, price in prices.items():
        if model.startswith(prefix):
            return price
    return (0.0, 0.0)


def analysis_metrics_from_trace(trace: dict, prices: dict[str, tuple[float, float]]) -> AnalysisMetrics | None:
    """OTLP JSON trace 에서 agent-service 의 분석 노드 스팬 1개와 그 직계 chat 스팬을 읽는다 — 게이트웨이 배치의 서버 측 chat 스팬은
    같은 호출의 중복 기록이라 세지 않는다."""
    spans: list[dict] = []
    for batch in trace.get("batches", []):
        service = next((str(next(iter(a["value"].values()))) for a in batch.get("resource", {}).get("attributes", []) if a["key"] == "service.name"), "")
        if service != AGENT_SERVICE:
            continue
        for scope in batch.get("scopeSpans", []):
            spans.extend(scope.get("spans", []))
    analysis = next((s for s in spans if s.get("name") == ANALYSIS_SPAN), None)
    if analysis is None:
        return None
    duration_s = (int(analysis["endTimeUnixNano"]) - int(analysis["startTimeUnixNano"])) / 1e9
    calls = input_tokens = output_tokens = 0
    cost = 0.0
    for span in spans:
        if span.get("parentSpanId") != analysis["spanId"] or not span.get("name", "").startswith(CHAT_SPAN_PREFIX):
            continue
        attributes = _attributes(span)
        prompt = int(float(attributes.get("gen_ai.usage.input_tokens", 0) or 0))
        completion = int(float(attributes.get("gen_ai.usage.output_tokens", 0) or 0))
        input_price, output_price = _price(attributes.get("gen_ai.response.model", ""), prices)
        calls += 1
        input_tokens += prompt
        output_tokens += completion
        cost += prompt / 1e6 * input_price + completion / 1e6 * output_price
    return AnalysisMetrics(duration_s=duration_s, llm_calls=calls, input_tokens=input_tokens, output_tokens=output_tokens, cost_usd=cost)


def _mean_or_none(values: list[float]) -> float | None:
    return mean(values) if values else None


def summarize(samples: list[Sample]) -> list[VariantStats]:
    stats = []
    for variant in sorted({s.variant for s in samples}):
        rows = [s for s in samples if s.variant == variant]
        measured = [s.metrics for s in rows if s.metrics is not None]
        stats.append(
            VariantStats(
                variant=variant,
                n=len(rows),
                faithfulness=mean(s.faithfulness for s in rows),
                actionability=mean(s.actionability for s in rows),
                severity_accuracy=mean(s.severity_accuracy for s in rows),
                low_quality_rate=sum(s.low_quality for s in rows) / len(rows),
                duration_s=_mean_or_none([m.duration_s for m in measured]),
                input_tokens=_mean_or_none([float(m.input_tokens) for m in measured]),
                output_tokens=_mean_or_none([float(m.output_tokens) for m in measured]),
                cost_usd=_mean_or_none([m.cost_usd for m in measured]),
                partial_rate=sum(s.partial for s in rows) / len(rows),
            )
        )
    return stats


def _fmt(value: float | None, spec: str) -> str:
    return "-" if value is None else format(value, spec)


def render_markdown(
    experiment: str,
    samples: list[Sample],
    stats: list[VariantStats],
    faithfulness_ci: tuple[float, float],
    cost_ci: tuple[float, float] | None,
    verdict: Verdict,
) -> str:
    lines = [f"### 실험 `{experiment}` — variant 별 집계", ""]
    lines += ["| variant | n | Faithfulness | Actionability | Severity | 저품질률 | partial 률 | 분석 시간(s) | 입력 토큰 | 출력 토큰 | 비용(USD) |", "|---|---|---|---|---|---|---|---|---|---|---|"]
    for s in stats:
        lines.append(
            f"| {s.variant} | {s.n} | {s.faithfulness:.3f} | {s.actionability:.3f} | {s.severity_accuracy:.3f} | {s.low_quality_rate:.0%} | {s.partial_rate:.0%} "
            f"| {_fmt(s.duration_s, '.1f')} | {_fmt(s.input_tokens, ',.0f')} | {_fmt(s.output_tokens, ',.0f')} | {_fmt(s.cost_usd, '.4f')} |"
        )
    lines += ["", "### 차이 (처리군 − control, 부트스트랩 95% CI, B=2000)", ""]
    lines.append(f"- Faithfulness: {faithfulness_ci[0]:+.3f} ~ {faithfulness_ci[1]:+.3f}")
    if cost_ci is not None:
        lines.append(f"- 비용(USD/건): {cost_ci[0]:+.4f} ~ {cost_ci[1]:+.4f}")
    lines += ["", f"### 판정: **{verdict.label}**", "", f"- {verdict.reason}", f"- 사전 기준: Faithfulness +{MIN_FAITHFULNESS_GAIN:.2f} 이상 · CI 가 0 미포함 · 비용 +{MAX_COST_INCREASE:.0%} 이내", ""]
    lines += ["### 표본 (품질 vs 비용 산점 표)", "", "| incident | variant | F | A | S | partial | 분석 시간(s) | LLM 호출 | 비용(USD) |", "|---|---|---|---|---|---|---|---|---|"]
    for s in sorted(samples, key=lambda x: (x.variant, x.incident_id)):
        m = s.metrics
        lines.append(
            f"| `{s.incident_id}` | {s.variant} | {s.faithfulness:.1f} | {s.actionability:.1f} | {s.severity_accuracy:.1f} | {'Y' if s.partial else ''} "
            f"| {_fmt(m.duration_s if m else None, '.1f')} | {m.llm_calls if m else '-'} | {_fmt(m.cost_usd if m else None, '.4f')} |"
        )
    return "\n".join(lines) + "\n"


# --- 원천 조회 -------------------------------------------------------------------------------------------------------


def build_client(args: argparse.Namespace) -> httpx.Client:
    if args.token_file:
        token = Path(args.token_file).read_text().strip()
        return httpx.Client(base_url=args.control_plane_url, headers={"Authorization": f"Bearer {token}"}, timeout=30.0)
    secret = os.environ.get(SECRET_ENV)
    if not secret:
        raise SystemExit(f"--token-file 또는 환경변수 {SECRET_ENV} 가 필요하다 (값은 infra/.env — 출력 금지)")
    auth = ClientCredentialsAuth(args.auth_token_url, args.client_id, secret, "ops:read")
    return httpx.Client(base_url=args.control_plane_url, auth=auth, timeout=30.0)


def _parse_iso(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def latest_per_incident(evaluations: list[dict], since: datetime | None, until: datetime | None) -> list[dict]:
    chosen: dict[str, dict] = {}
    for evaluation in evaluations:
        evaluated_at = _parse_iso(evaluation["evaluated_at"])
        if (since and evaluated_at < since) or (until and evaluated_at > until):
            continue
        current = chosen.get(evaluation["incident_id"])
        if current is None or _parse_iso(current["evaluated_at"]) < evaluated_at:
            chosen[evaluation["incident_id"]] = evaluation
    return list(chosen.values())


def paired_control(treatment_rows: list[dict], original_rows: list[dict], control_variant: str, suffix: str) -> list[dict]:
    """재생 실험(실험 2)의 control — 처리군 `<원본>-replay-<v>` 행마다 다른 실험의 원본 행(variant == control_variant)을 짝으로 고른다.
    원본이 없거나 원본의 variant 가 control 이 아니면(프롬프트가 다르면 변인이 둘) 그 짝은 제외한다."""
    originals = {row["incident_id"]: row for row in original_rows if row.get("experiment_variant") == control_variant}
    control = []
    for row in treatment_rows:
        incident_id = row["incident_id"]
        if incident_id.endswith(suffix) and incident_id[: -len(suffix)] in originals:
            control.append(originals[incident_id[: -len(suffix)]])
    return control


def fetch_report_status(client: httpx.Client, incident_id: str) -> str | None:
    response = client.get(f"/api/incidents/{incident_id}")
    if response.status_code != 200:
        return None
    return (response.json().get("report") or {}).get("status")


def fetch_analysis_metrics(tempo: httpx.Client, incident_id: str, since: datetime | None, prices: dict[str, tuple[float, float]]) -> AnalysisMetrics | None:
    start = int(since.timestamp()) if since else int(time.time()) - 48 * 3600
    response = tempo.get(
        "/api/search",
        params={"q": f'{{ name = "{ANALYSIS_SPAN}" && span.incident.id = "{incident_id}" }}', "start": start, "end": int(time.time()) + 60, "limit": 5},
    )
    response.raise_for_status()
    for trace in response.json().get("traces", []):
        detail = tempo.get(f"/api/traces/{trace['traceID']}")
        if detail.status_code != 200:
            continue
        metrics = analysis_metrics_from_trace(detail.json(), prices)
        if metrics is not None:
            return metrics
    return None


def collect_samples(client: httpx.Client, tempo: httpx.Client, args: argparse.Namespace, prices: dict[str, tuple[float, float]]) -> list[Sample]:
    response = client.get(f"/api/experiments/{args.experiment}/evaluations")
    response.raise_for_status()
    since = _parse_iso(args.since) if args.since else None
    until = _parse_iso(args.until) if args.until else None
    rows = latest_per_incident(response.json(), since, until)
    if args.control_from:
        # 재생 실험: 처리군 = 이 실험의 재생 행, control = 다른 실험의 원본 행(같은 인시던트, control variant 만) — 짝이 없는 재생은 뺀다
        other_experiment, other_variant = args.control_from.split(":", 1)
        other = client.get(f"/api/experiments/{other_experiment}/evaluations")
        other.raise_for_status()
        treatment_rows = [r for r in rows if r["experiment_variant"] != args.control]
        control_rows = paired_control(treatment_rows, latest_per_incident(other.json(), since, until), other_variant, args.replay_suffix)
        paired_ids = {r["incident_id"] + args.replay_suffix for r in control_rows}
        rows = [r for r in treatment_rows if r["incident_id"] in paired_ids] + [{**r, "experiment_variant": args.control} for r in control_rows]
    samples = []
    for evaluation in rows:
        scores = evaluation.get("scores") or {}
        incident_id = evaluation["incident_id"]
        status = fetch_report_status(client, incident_id)
        samples.append(
            Sample(
                incident_id=incident_id,
                variant=evaluation["experiment_variant"],
                faithfulness=float(scores["faithfulness"]["score"]),
                actionability=float(scores["actionability"]["score"]),
                severity_accuracy=float(scores["severity_accuracy"]["score"]),
                low_quality=bool(evaluation.get("low_quality")),
                partial=status == "partial",
                metrics=fetch_analysis_metrics(tempo, incident_id, since, prices),
            )
        )
        print(f"표본 {incident_id} variant={samples[-1].variant} F={samples[-1].faithfulness} metrics={'O' if samples[-1].metrics else 'X'}", file=sys.stderr)
    return samples


def load_prices(path: str | None) -> dict[str, tuple[float, float]]:
    if not path:
        return DEFAULT_PRICES
    raw = json.loads(Path(path).read_text())
    return {prefix: (float(p["input_per_mtok"]), float(p.get("output_per_mtok", 0.0))) for prefix, p in raw.items()}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--experiment", required=True)
    parser.add_argument("--control-plane-url", default="http://localhost:8081")
    parser.add_argument("--tempo-url", default="http://localhost:3200")
    parser.add_argument("--token-file", help="ops:read 토큰 파일 (미지정 시 client credentials)")
    parser.add_argument("--auth-token-url", default="http://localhost:8091/oauth2/token")
    parser.add_argument("--client-id", default=DEFAULT_CLIENT_ID)
    parser.add_argument("--since", help="이 시각(ISO) 이후 평가만 — 프롬프트 수정 전 표본을 섞지 않기 위해")
    parser.add_argument("--until")
    parser.add_argument("--control", default="A", help="control variant 이름 (experiments.yml 첫 variant)")
    parser.add_argument("--control-from", help="재생 실험용 — control 을 다른 실험의 원본 행에서 짝으로 가져온다 (예: analysis-prompt-v2:A)")
    parser.add_argument("--replay-suffix", default="-replay-B", help="재생 인시던트 id 접미 (replay_analysis.py 규약 <원본>-replay-<variant>)")
    parser.add_argument("--prices", help='단가 JSON {"claude-sonnet-5": {"input_per_mtok": 3.0, "output_per_mtok": 15.0}, ...}')
    parser.add_argument("--out", type=Path, help="마크다운 출력 파일 (미지정 시 stdout)")
    parser.add_argument("--samples-out", type=Path, help="표본 JSON 저장 (리포트 재현·부록)")
    args = parser.parse_args()

    prices = load_prices(args.prices)
    with build_client(args) as client, httpx.Client(base_url=args.tempo_url, timeout=30.0) as tempo:
        samples = collect_samples(client, tempo, args, prices)
    if not samples:
        raise SystemExit("표본이 없다 — 실험명·--since 를 확인")
    stats = summarize(samples)
    by_variant = {s.variant: s for s in stats}
    control = by_variant.get(args.control)
    treatments = [s for s in stats if s.variant != args.control]
    if control is None or not treatments:
        raise SystemExit(f"control({args.control}) 또는 처리군 표본이 없다: {[s.variant for s in stats]}")
    treatment = treatments[0]
    control_f = [s.faithfulness for s in samples if s.variant == control.variant]
    treatment_f = [s.faithfulness for s in samples if s.variant == treatment.variant]
    faithfulness_ci = bootstrap_diff_ci(treatment_f, control_f)
    control_cost = [s.metrics.cost_usd for s in samples if s.variant == control.variant and s.metrics]
    treatment_cost = [s.metrics.cost_usd for s in samples if s.variant == treatment.variant and s.metrics]
    cost_ci = bootstrap_diff_ci(treatment_cost, control_cost) if control_cost and treatment_cost else None
    verdict = decide(control, treatment, faithfulness_ci)
    markdown = render_markdown(args.experiment, samples, stats, faithfulness_ci, cost_ci, verdict)

    if args.samples_out:
        args.samples_out.write_text(json.dumps([{**s.__dict__, "metrics": s.metrics.__dict__ if s.metrics else None} for s in samples], ensure_ascii=False, indent=2))
    if args.out:
        args.out.write_text(markdown)
        print(f"리포트 저장 {args.out} (표본 {len(samples)}건, 판정 {verdict.label})", file=sys.stderr)
    else:
        print(markdown)


if __name__ == "__main__":
    main()
