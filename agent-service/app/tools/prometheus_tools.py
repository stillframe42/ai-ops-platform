"""Prometheus 조회 도구 — 직접 조회 (ADR-0002), base URL 은 settings.prometheus_url.

LLM 에이전트의 tool calling 용이므로 반환값은 JSON 문자열이다 (모델이 읽는 텍스트).
"""

import json
import time

import httpx
from langchain_core.tools import tool

from app.config import get_settings

# 테스트 주입 지점 — httpx.MockTransport 로 교체하면 스택 없이 검증 가능
_transport: httpx.BaseTransport | None = None


def _api_get(path: str, params: dict) -> dict:
    settings = get_settings()
    with httpx.Client(base_url=settings.prometheus_url, timeout=10.0, transport=_transport) as client:
        response = client.get(path, params=params)
    response.raise_for_status()
    payload = response.json()
    if payload.get("status") != "success":
        raise RuntimeError(f"Prometheus 응답 실패: {payload.get('error', payload)}")
    return payload["data"]


@tool
def query_prometheus(promql: str) -> str:
    """PromQL 순간값 조회 — 현재 시점의 메트릭 값을 반환한다.

    예: p95 latency 는
    histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri))
    """
    data = _api_get("/api/v1/query", {"query": promql})
    return json.dumps(data["result"], ensure_ascii=False)


@tool
def query_prometheus_range(promql: str, minutes: int) -> str:
    """PromQL 범위 조회 — 최근 minutes 분 구간의 추이를 반환한다.

    메모리 누수 같은 추세 판정에 사용한다 (예: jvm_memory_usage_after_gc 를 minutes=30 으로).
    """
    now = time.time()
    step_seconds = max(15, minutes)  # 창 크기와 무관하게 약 60개 포인트 유지 (15s 하한)
    data = _api_get(
        "/api/v1/query_range",
        {
            "query": promql,
            "start": now - minutes * 60,
            "end": now,
            "step": f"{step_seconds}s",
        },
    )
    return json.dumps(data["result"], ensure_ascii=False)


@tool
def get_active_alerts() -> str:
    """현재 발화 중(pending/firing)인 Prometheus Alert 목록을 반환한다 — scenario 라벨 포함."""
    data = _api_get("/api/v1/alerts", {})
    return json.dumps(data["alerts"], ensure_ascii=False)
