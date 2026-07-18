"""prometheus_tools 단위 테스트 — 실제 스택 없이 httpx.MockTransport 로 HTTP 계층을 검증한다."""

import json

import httpx
import pytest

from app.tools import prometheus_tools


def _success(data: dict) -> httpx.Response:
    return httpx.Response(200, json={"status": "success", "data": data})


def _install(monkeypatch, handler) -> None:
    monkeypatch.setattr(prometheus_tools, "_transport", httpx.MockTransport(handler))


def test_query_prometheus_returns_result_json(monkeypatch):
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/v1/query"
        assert request.url.params["query"] == 'up{job="target-app"}'
        return _success(
            {
                "resultType": "vector",
                "result": [{"metric": {"job": "target-app"}, "value": [1784266011.5, "1"]}],
            }
        )

    _install(monkeypatch, handler)
    out = prometheus_tools.query_prometheus.invoke({"promql": 'up{job="target-app"}'})

    result = json.loads(out)
    assert result[0]["value"][1] == "1"


def test_query_prometheus_range_builds_time_window(monkeypatch):
    captured: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/v1/query_range"
        captured.update(dict(request.url.params))
        return _success({"resultType": "matrix", "result": []})

    _install(monkeypatch, handler)
    prometheus_tools.query_prometheus_range.invoke(
        {"promql": "jvm_memory_usage_after_gc", "minutes": 30}
    )

    # 30분 창: end - start = 1800s, step 은 약 60개 포인트가 되도록 30s
    assert float(captured["end"]) - float(captured["start"]) == pytest.approx(1800, abs=1)
    assert captured["step"] == "30s"


def test_query_prometheus_range_step_has_floor(monkeypatch):
    captured: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured.update(dict(request.url.params))
        return _success({"resultType": "matrix", "result": []})

    _install(monkeypatch, handler)
    prometheus_tools.query_prometheus_range.invoke({"promql": "up", "minutes": 5})

    # 짧은 창에서도 스크레이프 간격(5s)보다 성긴 15s 하한 유지
    assert captured["step"] == "15s"


def test_get_active_alerts_returns_alerts_with_labels(monkeypatch):
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/v1/alerts"
        return _success(
            {
                "alerts": [
                    {
                        "labels": {"alertname": "TargetAppHighLatency", "scenario": "latency-surge"},
                        "state": "firing",
                        "activeAt": "2026-07-17T05:00:00Z",
                    }
                ]
            }
        )

    _install(monkeypatch, handler)
    out = prometheus_tools.get_active_alerts.invoke({})

    alerts = json.loads(out)
    assert alerts[0]["labels"]["scenario"] == "latency-surge"
    assert alerts[0]["state"] == "firing"


def test_compare_with_baseline_evaluates_now_and_one_hour_ago(monkeypatch):
    captured: list[dict[str, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/v1/query"
        captured.append(dict(request.url.params))
        return _success(
            {
                "resultType": "vector",
                "result": [{"metric": {}, "value": [1784266011.5, "2.0"]}],
            }
        )

    _install(monkeypatch, handler)
    out = prometheus_tools.compare_with_baseline.invoke(
        {"promql": "sum(rate(http_server_requests_seconds_count[5m]))"}
    )

    # 같은 표현식을 두 시점에 평가: 현재(time 미지정) + 1시간 전(time 지정)
    assert len(captured) == 2
    assert "time" not in captured[0]
    assert "time" in captured[1]
    result = json.loads(out)
    assert set(result) == {"current", "baseline_1h_ago"}


def test_compare_with_baseline_time_param_is_one_hour_back(monkeypatch):
    captured: list[dict[str, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(dict(request.url.params))
        return _success({"resultType": "vector", "result": []})

    _install(monkeypatch, handler)
    import time as time_module

    before = time_module.time()
    prometheus_tools.compare_with_baseline.invoke({"promql": "up"})

    assert float(captured[1]["time"]) == pytest.approx(before - 3600, abs=5)


def test_error_status_raises(monkeypatch):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200, json={"status": "error", "errorType": "bad_data", "error": "parse error"}
        )

    _install(monkeypatch, handler)
    with pytest.raises(RuntimeError, match="parse error"):
        prometheus_tools.query_prometheus.invoke({"promql": "up{"})
