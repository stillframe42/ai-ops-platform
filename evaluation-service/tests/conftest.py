import os

import pytest
from opentelemetry.sdk._logs.export import InMemoryLogRecordExporter, SimpleLogRecordProcessor
from opentelemetry.sdk.metrics.export import InMemoryMetricReader

# 전역 provider 는 한 번만 세울 수 있다 — 세션 시작 시 인메모리 리더로 조립해 이벤트·히스토그램 테스트가 읽게 한다
METRIC_READER = InMemoryMetricReader()
LOG_EXPORTER = InMemoryLogRecordExporter()


@pytest.fixture(autouse=True, scope="session")
def required_auth_secret():
    """인증 항상 필수(ADR-0016)로 AUTH_CLIENT_SECRET 은 기본값이 없다 — 테스트 세션은 더미 값으로 고정해
    Settings() 조립 자체는 통과시킨다 (실 토큰 발급 경로는 테스트하지 않는다)."""
    os.environ.setdefault("AUTH_CLIENT_SECRET", "test-secret")
    from evaluation.config.otel import setup_telemetry

    setup_telemetry(metric_reader=METRIC_READER, log_processor=SimpleLogRecordProcessor(LOG_EXPORTER))
    yield
