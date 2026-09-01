"""tool_gating 단위 테스트 — 레드팀 RT-14·15 의 공격 인자를 결정론 케이스로 고정한다."""

import httpx
import pytest

from app.security.tool_gating import validate_log_level, validate_minutes, validate_promql
from app.tools import loki_tools, prometheus_tools


# ── PromQL 검증 ──


@pytest.mark.parametrize(
    "promql",
    [
        "up",
        'up{job="target-app"}',
        "jvm_memory_usage_after_gc",
        "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri))",
        'sum(rate(http_server_requests_seconds_count{status=~"5.."}[3m])) / sum(rate(http_server_requests_seconds_count[3m]))',
        'jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}',
        "sum(rate(jvm_gc_pause_seconds_count[5m]))",
    ],
)
def test_validate_promql_allows_agent_prompt_queries(promql):
    # 에이전트 프롬프트가 안내하는 정상 질의 전수 — 게이팅이 정상 경로를 깨면 안 된다
    assert validate_promql(promql) == promql


def test_validate_promql_rejects_metric_outside_whitelist():
    # RT-14 공격 인자 그대로 — 다른 job 의 메트릭 조회
    with pytest.raises(ValueError, match="허용되지 않은 메트릭: process_cpu_seconds_total"):
        validate_promql('process_cpu_seconds_total{job="kube-state-metrics"}')


def test_validate_promql_rejects_function_outside_whitelist():
    with pytest.raises(ValueError, match="허용되지 않은 PromQL 함수: label_replace"):
        validate_promql('label_replace(up, "a", "$1", "job", "(.*)")')


def test_validate_promql_ignores_label_names_and_values():
    # 라벨 이름(uri·status)·라벨 값 문자열·by 절 라벨 목록은 메트릭 검사 대상이 아니다
    assert validate_promql('sum(rate(http_server_requests_seconds_count{uri="/products", status=~"5.."}[3m])) by (uri)')


def test_validate_promql_rejects_metric_hidden_in_binary_expression():
    with pytest.raises(ValueError, match="node_filesystem_avail_bytes"):
        validate_promql("up + node_filesystem_avail_bytes")


# ── minutes·level 검증 ──


@pytest.mark.parametrize("minutes", [0, -5, 1441, "30", 2.5, True])
def test_validate_minutes_rejects_out_of_range(minutes):
    with pytest.raises(ValueError, match="minutes"):
        validate_minutes(minutes)


def test_validate_minutes_allows_normal_window():
    assert validate_minutes(30) == 30


def test_validate_log_level_rejects_logql_injection():
    # RT-15 공격 인자 그대로 — level 로 LogQL 필터 탈출
    with pytest.raises(ValueError, match="허용되지 않은 로그 레벨"):
        validate_log_level('ERROR" or log_level=~".+" | service=~".+')


def test_validate_log_level_allows_standard_levels():
    for level in ("TRACE", "DEBUG", "INFO", "WARN", "ERROR"):
        assert validate_log_level(level) == level


# ── 도구 본문 통합 — 검증 실패 시 HTTP 전송 자체가 없어야 한다 ──


def _install_failing_transport(monkeypatch, module):
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("검증 실패 인자가 HTTP 로 전송됨")

    monkeypatch.setattr(module, "_transport", httpx.MockTransport(handler))


def test_query_prometheus_blocks_before_http(monkeypatch):
    _install_failing_transport(monkeypatch, prometheus_tools)
    with pytest.raises(ValueError, match="허용되지 않은 메트릭"):
        prometheus_tools.query_prometheus.invoke({"promql": 'process_cpu_seconds_total{job="kube-state-metrics"}'})


def test_query_prometheus_range_blocks_bad_minutes_before_http(monkeypatch):
    _install_failing_transport(monkeypatch, prometheus_tools)
    with pytest.raises(ValueError, match="minutes"):
        prometheus_tools.query_prometheus_range.invoke({"promql": "up", "minutes": 100000})


def test_compare_with_baseline_blocks_before_http(monkeypatch):
    _install_failing_transport(monkeypatch, prometheus_tools)
    with pytest.raises(ValueError, match="허용되지 않은 메트릭"):
        prometheus_tools.compare_with_baseline.invoke({"promql": "node_load1"})


def test_get_app_logs_blocks_level_injection_before_http(monkeypatch):
    _install_failing_transport(monkeypatch, loki_tools)
    with pytest.raises(ValueError, match="허용되지 않은 로그 레벨"):
        loki_tools.get_app_logs.invoke({"minutes": 5, "level": 'ERROR" or log_level=~".+" | service=~".+'})
