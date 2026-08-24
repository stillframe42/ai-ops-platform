"""loki_tools 단위 테스트 — 실제 스택 없이 httpx.MockTransport 로 HTTP 계층을 검증한다.

prometheus_tools 와 동일한 _transport 시임 주입 패턴.
"""

import json

import httpx
import pytest

from app.tools import loki_tools


def _success(result: list) -> httpx.Response:
    return httpx.Response(
        200,
        json={"status": "success", "data": {"resultType": "streams", "result": result}},
    )


def _install(monkeypatch, handler) -> None:
    monkeypatch.setattr(loki_tools, "_transport", httpx.MockTransport(handler))


def test_get_app_logs_builds_logql_and_window(monkeypatch):
    captured: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/loki/api/v1/query_range"
        captured.update(dict(request.url.params))
        return _success([])

    _install(monkeypatch, handler)
    loki_tools.get_app_logs.invoke({"minutes": 30, "level": "ERROR"})

    # 실측 검증 필터 그대로: json 파서 + 평탄화된 log_level 필드
    assert captured["query"] == '{service="target-app"} | json | log_level="ERROR"'
    # 창 크기 30분 (Loki 는 나노초 타임스탬프)
    window_ns = int(captured["end"]) - int(captured["start"])
    assert window_ns == pytest.approx(1800 * 1_000_000_000, rel=0.01)
    # 최신 로그 우선 + 토큰 비용 상한
    assert captured["direction"] == "backward"
    assert captured["limit"] == "50"


def test_get_app_logs_flattens_streams_to_lines(monkeypatch):
    def handler(request: httpx.Request) -> httpx.Response:
        return _success(
            [
                {
                    "stream": {"service": "target-app"},
                    "values": [
                        ["1784700000000000000", '{"log":{"level":"ERROR"},"message":"boom-1"}'],
                        ["1784700001000000000", '{"log":{"level":"ERROR"},"message":"boom-2"}'],
                    ],
                },
                {
                    "stream": {"service": "target-app", "detected_level": "error"},
                    "values": [
                        ["1784700002000000000", '{"log":{"level":"ERROR"},"message":"boom-3"}'],
                    ],
                },
            ]
        )

    _install(monkeypatch, handler)
    out = loki_tools.get_app_logs.invoke({"minutes": 10, "level": "ERROR"})

    # 스트림 구분·Loki 타임스탬프는 버리고 로그 라인만 — 라인 자체가 @timestamp 를 포함한 JSON
    lines = json.loads(out)
    assert lines == [
        '{"log":{"level":"ERROR"},"message":"boom-1"}',
        '{"log":{"level":"ERROR"},"message":"boom-2"}',
        '{"log":{"level":"ERROR"},"message":"boom-3"}',
    ]


def test_get_app_logs_error_status_raises(monkeypatch):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200, json={"status": "error", "errorType": "bad_data", "error": "parse error"}
        )

    _install(monkeypatch, handler)
    with pytest.raises(RuntimeError, match="parse error"):
        loki_tools.get_app_logs.invoke({"minutes": 10, "level": "ERROR"})
