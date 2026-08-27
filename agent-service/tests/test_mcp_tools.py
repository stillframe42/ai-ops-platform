"""mcp_tools 단위 테스트 — 연결 구성·토큰 주입 규약 검증 (실 MCP 서버 무의존).

도구 발견(tools/list) 자체는 어댑터 몫 — 여기서는 우리가 만드는 연결 구성이
Streamable HTTP + Client Credentials 토큰 규약을 지키는지만 검증한다.
"""

from app.config.settings import Settings
from app.tools import mcp_tools
from app.tools.oauth_client import ClientCredentialsAuth


def _settings(**overrides) -> Settings:
    # env_file 무시 — 테스트는 명시 인자만으로 구성 (로컬 .env 의 실 키가 새지 않게)
    return Settings(_env_file=None, auth_client_secret="test-secret", **overrides)


def test_connections_default_streamable_http_with_auth():
    connections = mcp_tools.build_mcp_connections(_settings())

    conn = connections[mcp_tools.MCP_SERVER_NAME]
    assert conn["transport"] == "streamable_http"
    # 로컬 기본값 — 호스트에서 실행 시 control-plane 호스트 포트 (compose 는 env 로 덮어씀)
    assert conn["url"] == "http://localhost:8081/mcp"
    # 인증은 헤더 고정값이 아니라 Auth 객체 — 토큰 발급·재발급을 요청 시점에 처리
    assert isinstance(conn["auth"], ClientCredentialsAuth)
    assert "headers" not in conn


def test_connections_override_urls():
    connections = mcp_tools.build_mcp_connections(
        _settings(
            mcp_server_url="http://control-plane:8080/mcp",
            auth_token_url="http://auth-server:8091/oauth2/token",
        )
    )

    conn = connections[mcp_tools.MCP_SERVER_NAME]
    assert conn["url"] == "http://control-plane:8080/mcp"
    assert conn["auth"]._token_url == "http://auth-server:8091/oauth2/token"
    assert conn["auth"]._scope == "ops:read llm:invoke"


def test_client_secret_is_required(monkeypatch):
    # 인증 항상 필수 — 시크릿 미설정은 기동 실패로 드러나야 한다 (조용한 무인증 상태 없음)
    import pytest
    from pydantic import ValidationError

    monkeypatch.delenv("AUTH_CLIENT_SECRET", raising=False)
    with pytest.raises(ValidationError):
        Settings(_env_file=None)


def test_server_name_matches_control_plane():
    # 서버 이름은 control-plane 의 spring.ai.mcp.server.name 과 일치 (관측·로그 대조용)
    assert mcp_tools.MCP_SERVER_NAME == "ops-control-plane"
