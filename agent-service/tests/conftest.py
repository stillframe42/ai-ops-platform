import os

import pytest


@pytest.fixture(autouse=True, scope="session")
def required_auth_secret():
    """인증 항상 필수(ADR-0016)로 AUTH_CLIENT_SECRET 은 기본값이 없다 — 테스트 세션은 더미 값으로 고정해
    Settings() 조립 자체는 통과시키고, 실 토큰 발급은 각 테스트가 스텁으로 대체한다."""
    os.environ.setdefault("AUTH_CLIENT_SECRET", "test-secret")
    yield
