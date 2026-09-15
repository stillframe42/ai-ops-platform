"""create_llm 게이트웨이 계약 테스트 — 판정 헤더 흡수·라우팅 헤더·인증 배선의 계약 고정.

게이트웨이 판정(캐시·가드레일·다운그레이드·폴백)은 전부 응답 헤더로 도착한다 — 헤더를 response_metadata 로
흡수하면 상태·체크포인트에서 판정을 볼 수 있다 (스팬 승격은 test_otel 몫). 여기서는 그 배선 계약만 고정한다.
"""

import httpx

from app.config.llm import create_llm
from app.config.settings import Settings
from app.tools.oauth_client import ClientCredentialsAuth


def _settings(**overrides) -> Settings:
    return Settings(_env_file=None, **overrides)


def test_llm_absorbs_gateway_response_headers():
    """응답 헤더 흡수 활성 — 게이트웨이 판정이 AIMessage.response_metadata["headers"] 에 실린다."""
    llm = create_llm(_settings(), task_type="root-cause-analysis")

    assert llm.include_response_headers is True


def test_llm_sends_routing_header_but_no_self_declared_service():
    """라우팅 키(X-Task-Type)는 송신, 비용 식별자는 헤더 자기 신고 대신 토큰 client_id (ADR-0016)."""
    llm = create_llm(_settings(), task_type="root-cause-analysis")

    assert llm.default_headers["X-Task-Type"] == "root-cause-analysis"
    assert "X-Client-Service" not in llm.default_headers


def test_llm_http_clients_carry_client_credentials_auth():
    """동기·비동기 httpx 클라이언트 양쪽에 같은 ClientCredentialsAuth — 한쪽만 주입하면 나머지는 무토큰 경로."""
    llm = create_llm(_settings(), task_type="root-cause-analysis")

    assert isinstance(llm.http_client, httpx.Client)
    assert isinstance(llm.http_async_client, httpx.AsyncClient)
    assert isinstance(llm.http_client.auth, ClientCredentialsAuth)
    assert llm.http_async_client.auth is llm.http_client.auth


def test_llm_sends_experiment_variant_header_only_when_given():
    """실험 모델 variant 는 요청 헤더 X-Experiment-Variant 로 — 게이트웨이가 정의된 variant 만 모델을 바꾼다 (ADR-0019)."""
    plain = create_llm(_settings(), task_type="root-cause-analysis")
    assert "X-Experiment-Variant" not in plain.default_headers
    tagged = create_llm(_settings(), task_type="root-cause-analysis", extra_headers={"X-Experiment-Variant": "analysis-model-haiku:B"})
    assert tagged.default_headers["X-Experiment-Variant"] == "analysis-model-haiku:B"
    assert tagged.default_headers["X-Task-Type"] == "root-cause-analysis"
