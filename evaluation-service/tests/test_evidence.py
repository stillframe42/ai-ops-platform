"""시간창 재조회 테스트 — httpx.MockTransport 로 Prometheus·Loki 왕복을 스택 없이 검증한다."""

import asyncio
from datetime import UTC, datetime

import httpx

from evaluation.evidence import (
    QUERIES,
    EvidenceCollector,
    evidence_window,
    incident_started_at,
    is_empty,
    render_evidence,
)

INCIDENT_ID = "inc-error-rate-surge-20260902055408-e2eaf4"


def test_window_starts_five_minutes_before_incident_timestamp_and_ends_at_completed_at():
    start, end = evidence_window(INCIDENT_ID, "2026-09-02T05:55:29.419986+00:00")
    assert incident_started_at(INCIDENT_ID) == datetime(2026, 9, 2, 5, 54, 8, tzinfo=UTC)
    assert start == datetime(2026, 9, 2, 5, 49, 8, tzinfo=UTC)
    assert end == datetime(2026, 9, 2, 5, 55, 29, 419986, tzinfo=UTC)


def test_window_end_is_capped_at_five_minutes_after_incident_start():
    """승인 대기·만료로 completed_at 이 밀려도 다음 회차의 chaos 가 근거에 섞이지 않게 창 끝을 상한으로 자른다."""
    start, end = evidence_window(INCIDENT_ID, "2026-09-02T06:47:00+00:00")
    assert start == datetime(2026, 9, 2, 5, 49, 8, tzinfo=UTC)
    assert end == datetime(2026, 9, 2, 5, 59, 8, tzinfo=UTC)


def test_window_falls_back_when_incident_id_has_no_timestamp():
    now = datetime(2026, 9, 10, 4, 0, tzinfo=UTC)
    start, end = evidence_window("inc-manual", None, now=now)
    assert end == now and (end - start).total_seconds() == 15 * 60


def test_collect_queries_all_series_and_both_log_levels():
    seen: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append({"host": request.url.host, "path": request.url.path, **dict(request.url.params)})
        if request.url.host == "prom":
            return httpx.Response(200, json={"status": "success", "data": {"resultType": "matrix", "result": [
                {"metric": {"status": "500"}, "values": [[1756792200, "0.49731"], [1756792230, "0.5"]]},
            ]}})
        line = '{"@timestamp":"2026-09-02T05:54:10.123Z","message":"chaos error-rate fault 로 500 반환","log.level":"ERROR"}'
        return httpx.Response(200, json={"status": "success", "data": {"resultType": "streams", "result": [
            {"stream": {"service": "target-app"}, "values": [["1756792450123000000", line]]},
        ]}})

    collector = EvidenceCollector("http://prom", "http://loki", transport=httpx.MockTransport(handler))
    evidence = asyncio.run(collector.collect(INCIDENT_ID, "2026-09-02T05:55:29+00:00"))

    prom_calls = [s for s in seen if s["host"] == "prom"]
    assert [s["query"] for s in prom_calls] == list(QUERIES.values())
    assert prom_calls[0]["start"] == "2026-09-02T05:49:08Z" and prom_calls[0]["end"] == "2026-09-02T05:55:29Z"
    assert prom_calls[0]["step"] == "30s"
    loki_calls = [s for s in seen if s["host"] == "loki"]
    assert [s["query"] for s in loki_calls] == [
        '{service="target-app"} | json | log_level="ERROR"',
        '{service="target-app"} | json | log_level="WARN"',
    ]
    assert loki_calls[0]["direction"] == "forward" and loki_calls[0]["limit"] == "50"
    assert evidence["window"] == ["2026-09-02T05:49:08Z", "2026-09-02T05:55:29Z"]
    assert evidence["prometheus"]["error_ratio"][0]["values"] == [(1756792200, 0.4973), (1756792230, 0.5)]
    assert evidence["loki"]["ERROR"] == ["2026-09-02T05:54:10 chaos error-rate fault 로 500 반환"]
    assert not is_empty(evidence)


def test_empty_result_is_treated_as_out_of_retention():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"status": "success", "data": {"resultType": "matrix", "result": []}})

    collector = EvidenceCollector("http://prom", "http://loki", transport=httpx.MockTransport(handler))
    evidence = asyncio.run(collector.collect("inc-latency-surge-20260828054042-phase3", None))

    assert is_empty(evidence)
    assert render_evidence(evidence).startswith("재조회 불가")


def test_render_evidence_summarises_series_and_logs():
    evidence = {
        "incident_id": INCIDENT_ID,
        "window": ["2026-09-02T05:49:08Z", "2026-09-02T05:55:29Z"],
        "prometheus": {
            "error_ratio": [{"metric": {}, "values": [(1, 0.0), (2, 0.4973)]}],
            "rate_by_status": [], "p95_seconds": [], "heap_ratio": [],
        },
        "loki": {"ERROR": ["05:54:10 fault"], "WARN": []},
    }
    text = render_evidence(evidence)
    assert "(합계): min 0.0 · max 0.4973 · 마지막 0.4973 (2점)" in text
    assert "ERROR 1건 · WARN 0건" in text and "[ERROR] 05:54:10 fault" in text
