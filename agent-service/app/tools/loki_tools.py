"""Loki 로그 조회 도구 — 직접 조회 (ADR-0002), base URL 은 settings.loki_url.

ADR-0004 의 "2단계: 에이전트 LogQL 도구" 확정 구현. 필터는 실측 검증분:
{service="target-app"} | json | log_level="ERROR"
"""

import json
import time

import httpx
from langchain_core.tools import tool

from app.config import get_settings
from app.security.tool_gating import validate_log_level, validate_minutes
from app.security.untrusted import wrap_untrusted

# 테스트 주입 지점 — httpx.MockTransport 로 교체하면 스택 없이 검증 가능
_transport: httpx.BaseTransport | None = None

# 반환 라인 수 상한 — 로그 라인은 그대로 LLM 입력 토큰이 된다
_MAX_LINES = 50


def _api_get(path: str, params: dict) -> dict:
    settings = get_settings()
    with httpx.Client(base_url=settings.loki_url, timeout=10.0, transport=_transport) as client:
        response = client.get(path, params=params)
    response.raise_for_status()
    payload = response.json()
    if payload.get("status") != "success":
        raise RuntimeError(f"Loki 응답 실패: {payload.get('error', payload)}")
    return payload["data"]


@tool
def get_app_logs(minutes: int, level: str = "ERROR") -> str:
    """target-app 의 최근 minutes 분 로그를 level 로 필터링해 반환한다 (최신순, 최대 50건).

    로그는 JSON 구조화 형식 — @timestamp, log.level, log.logger, message 필드를 포함한다.
    예: 5xx 원인 조사는 get_app_logs(minutes=10, level="ERROR")
    """
    # level 은 LogQL 문자열에 삽입되는 LLM 자유 인자 — 화이트리스트 밖이면 필터 탈출이 된다 (RT-15)
    validate_log_level(level)
    validate_minutes(minutes)
    now_ns = int(time.time() * 1_000_000_000)
    data = _api_get(
        "/loki/api/v1/query_range",
        {
            "query": f'{{service="target-app"}} | json | log_level="{level}"',
            "start": now_ns - minutes * 60 * 1_000_000_000,
            "end": now_ns,
            "limit": _MAX_LINES,
            "direction": "backward",
        },
    )
    # 스트림 구분·Loki 타임스탬프는 제외하고 로그 라인만 — 라인 자체가 @timestamp 포함 JSON
    lines = [line for stream in data["result"] for _, line in stream["values"]]
    # 로그 본문은 외부 요청이 그대로 실리는 비신뢰 데이터 (위협 모델 ①) — 구분자로 격리
    return wrap_untrusted("loki-logs", json.dumps(lines, ensure_ascii=False))
