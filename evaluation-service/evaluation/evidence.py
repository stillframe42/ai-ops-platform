"""평가 입력 — 인시던트 시간창 재조회 (docs/quality-evaluation.md §4).

보고서 페이로드에는 도구 호출 원본이 없다 (`monitoring.evidences` 는 질의 문자열, `analysis.evidence` 는 LLM 요약).
Faithfulness 판정에는 에이전트와 무관한 실측이 필요하므로 같은 시간창으로 Prometheus·Loki 를 다시 조회한다.
질의는 고정 4종 + 로그 2레벨 — LLM 자유 입력이 없어 화이트리스트 검증(agent-service tool_gating)이 필요 없다.
"""

import json
import re
from datetime import UTC, datetime, timedelta

import httpx

# 창 시작 = 발화(incident_id 타임스탬프) 5분 전 — 발화 전 rate 창(최장 5m)의 상승 구간까지 포함
WINDOW_BEFORE = timedelta(minutes=5)
# 창 끝 상한 = 발화 + 5분 — 에이전트의 관측(monitor 60s + analysis 180s 상한)은 이 안에 끝나는데 completed_at 은 승인 대기·만료
# (최장 60분)까지 밀린다. 그대로 쓰면 뒤이은 다른 인시던트의 chaos 가 근거에 섞인다 (2026-09-10 실측: memory 케이스 창에
# 7분 뒤 회차의 error-rate 5xx 가 들어옴 — 10분 상한으로도 부족했다)
WINDOW_AFTER_MAX = timedelta(minutes=5)
# incident_id 를 못 읽을 때의 대체 — 종결 시각에서 거슬러 잡는 창 길이
FALLBACK_WINDOW = timedelta(minutes=15)
STEP = "30s"
MAX_LOG_LINES = 50
LOG_LEVELS = ("ERROR", "WARN")

# Alert 규칙(target-app-alerts.yml)과 같은 job 필터 — 다른 JVM 앱(control-plane·llm-gateway)의 요청이 비율을 희석하지 않게
QUERIES = {
    "error_ratio": (
        'sum(rate(http_server_requests_seconds_count{job="target-app",status=~"5.."}[1m])) '
        '/ sum(rate(http_server_requests_seconds_count{job="target-app"}[1m]))'
    ),
    "rate_by_status": 'sum(rate(http_server_requests_seconds_count{job="target-app"}[1m])) by (status)',
    "p95_seconds": (
        'histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{job="target-app"}[1m])) by (le))'
    ),
    "heap_ratio": (
        'max(jvm_memory_used_bytes{job="target-app",area="heap"}) / max(jvm_memory_max_bytes{job="target-app",area="heap"})'
    ),
}

_ID_TIMESTAMP = re.compile(r"-(\d{14})-")


def incident_started_at(incident_id: str) -> datetime | None:
    """incident_id 의 발화 타임스탬프(YYYYMMDDHHMMSS, UTC) — control-plane 이 Alert 발화 시각으로 만든다."""
    m = _ID_TIMESTAMP.search(incident_id)
    if not m:
        return None
    return datetime.strptime(m.group(1), "%Y%m%d%H%M%S").replace(tzinfo=UTC)


def _parse_iso(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)


def evidence_window(incident_id: str, completed_at: str | None, now: datetime | None = None) -> tuple[datetime, datetime]:
    """(창 시작, 창 끝). 시작 = 발화 5분 전, 끝 = min(completed_at, 발화 + 5분). completed_at 이 없으면 지금.

    `incident_reports.created_at` 은 수신 시각이라 쓰지 않는다. 발화 시각을 못 읽으면 끝에서 15분을 거슬러 잡는다.
    """
    end = _parse_iso(completed_at) or now or datetime.now(UTC)
    started = incident_started_at(incident_id)
    if started is None:
        return end - FALLBACK_WINDOW, end
    end = min(end, started + WINDOW_AFTER_MAX)
    start = started - WINDOW_BEFORE
    if start >= end:
        end = start + FALLBACK_WINDOW
    return start, end


def _iso(dt: datetime) -> str:
    return dt.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


class EvidenceCollector:
    """Prometheus query_range 4종 + Loki ERROR/WARN — 결과 형식은 골든셋 시트 생성기(`*.requery.json`)와 같다."""

    def __init__(
        self,
        prometheus_url: str,
        loki_url: str,
        *,
        transport: httpx.AsyncBaseTransport | None = None,  # 테스트 주입 지점 — httpx.MockTransport
        timeout: float = 15.0,
    ) -> None:
        self._prometheus_url = prometheus_url
        self._loki_url = loki_url
        self._transport = transport
        self._timeout = timeout

    async def collect(self, incident_id: str, completed_at: str | None) -> dict:
        start, end = evidence_window(incident_id, completed_at)
        out: dict = {"incident_id": incident_id, "window": [_iso(start), _iso(end)], "prometheus": {}, "loki": {}}
        async with httpx.AsyncClient(transport=self._transport, timeout=self._timeout) as client:
            for name, query in QUERIES.items():
                out["prometheus"][name] = await self._prometheus_range(client, query, start, end)
            for level in LOG_LEVELS:
                out["loki"][level] = await self._loki_lines(client, level, start, end)
        return out

    async def _prometheus_range(self, client: httpx.AsyncClient, query: str, start: datetime, end: datetime) -> list[dict]:
        response = await client.get(
            f"{self._prometheus_url}/api/v1/query_range",
            params={"query": query, "start": _iso(start), "end": _iso(end), "step": STEP},
        )
        response.raise_for_status()
        payload = response.json()
        if payload.get("status") != "success":
            raise RuntimeError(f"Prometheus 응답 실패: {payload.get('error', payload)}")
        return [
            {"metric": series["metric"], "values": [(point[0], round(float(point[1]), 4)) for point in series["values"]]}
            for series in payload["data"]["result"]
        ]

    async def _loki_lines(self, client: httpx.AsyncClient, level: str, start: datetime, end: datetime) -> list[str]:
        response = await client.get(
            f"{self._loki_url}/loki/api/v1/query_range",
            params={
                "query": f'{{service="target-app"}} | json | log_level="{level}"',
                "start": _iso(start),
                "end": _iso(end),
                "limit": MAX_LOG_LINES,
                "direction": "forward",  # 창 앞쪽(발화 직후)부터 — 상한에 걸려도 원인 구간이 남는다
            },
        )
        response.raise_for_status()
        payload = response.json()
        if payload.get("status") != "success":
            raise RuntimeError(f"Loki 응답 실패: {payload.get('error', payload)}")
        lines: list[str] = []
        for stream in payload["data"]["result"]:
            for _, raw in stream["values"]:
                # ECS JSON 로그에서 시각·메시지만 — 메타데이터는 판정에 불필요하고 토큰만 늘린다
                try:
                    record = json.loads(raw)
                    lines.append(f"{record.get('@timestamp', '')[:19]} {record.get('message', raw)[:220]}")
                except (json.JSONDecodeError, TypeError):
                    lines.append(raw[:240])
        return lines


def is_empty(evidence: dict | None) -> bool:
    """재조회는 했으나 시리즈·로그가 전부 비면 보존 밖(또는 창 오류)으로 취급한다."""
    if evidence is None:
        return True
    return not any(evidence["prometheus"].values()) and not any(evidence["loki"].values())


def _series_summary(series: list[dict]) -> str:
    if not series:
        return "결과 없음 (시리즈 0)"
    rows = []
    for s in series:
        vals = [v[1] for v in s["values"]]
        label = ", ".join(f"{k}={v}" for k, v in s["metric"].items()) or "(합계)"
        rows.append(f"  - {label}: min {min(vals)} · max {max(vals)} · 마지막 {vals[-1]} ({len(vals)}점)")
    return "\n".join(rows)


def render_evidence(evidence: dict | None) -> str:
    """재조회 결과 → 사람(라벨링 시트)과 Judge 가 같은 문장으로 읽는 요약. 비면 '재조회 불가' 문구."""
    if is_empty(evidence):
        return (
            "재조회 불가 — 보존 기간 밖 (Prometheus 10d · Loki 2026-08-28 이후). "
            "보고서 내부 정합과 주입 사실만으로 판정한다."
        )
    prom = evidence["prometheus"]
    lines = [
        f"시간창: {evidence['window'][0]} ~ {evidence['window'][1]} (발화 5분 전 ~ 종결, 끝 상한 = 발화 + 5분)",
        "",
        "Prometheus (30s step):",
        f"- 5xx 비율 `sum(rate 5xx) / sum(rate all)`:\n{_series_summary(prom['error_ratio'])}",
        f"- status 별 요청률:\n{_series_summary(prom['rate_by_status'])}",
        f"- p95 (초):\n{_series_summary(prom['p95_seconds'])}",
        f"- heap 사용 비율:\n{_series_summary(prom['heap_ratio'])}",
        "",
        f"Loki `{{service=\"target-app\"}}` ERROR {len(evidence['loki']['ERROR'])}건 · "
        f"WARN {len(evidence['loki']['WARN'])}건 (창 안 {MAX_LOG_LINES}줄 상한)",
    ]
    for level in LOG_LEVELS:
        for line in evidence["loki"][level][:5]:
            lines.append(f"  - [{level}] {line[:200]}")
    return "\n".join(lines)
