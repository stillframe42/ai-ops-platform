"""mcp_tools 단위 테스트 — 연결 구성·헤더 주입 규약 검증 (실 MCP 서버 무의존).

도구 발견(tools/list) 자체는 어댑터 몫 — 여기서는 우리가 만드는 연결 구성이
Streamable HTTP + API Key 헤더 규약을 지키는지만 검증한다.
"""

from app.config.settings import Settings
from app.tools import mcp_tools


def _settings(**overrides) -> Settings:
    # env_file 무시 — 테스트는 명시 인자만으로 구성 (로컬 .env 의 실 키가 새지 않게)
    return Settings(_env_file=None, **overrides)


def test_connections_default_streamable_http():
    connections = mcp_tools.build_mcp_connections(_settings())

    conn = connections[mcp_tools.MCP_SERVER_NAME]
    assert conn["transport"] == "streamable_http"
    # 로컬 기본값 — 호스트에서 실행 시 control-plane 호스트 포트 (compose 는 env 로 덮어씀)
    assert conn["url"] == "http://localhost:8081/mcp"


def test_connections_without_api_key_has_no_headers():
    # 키 미설정이면 헤더 자체를 넣지 않는다 — 서버 필터도 키 미설정이면 인증 생략 (정합)
    connections = mcp_tools.build_mcp_connections(_settings())

    assert "headers" not in connections[mcp_tools.MCP_SERVER_NAME]


def test_connections_with_api_key_injects_header():
    connections = mcp_tools.build_mcp_connections(
        _settings(mcp_server_url="http://control-plane:8080/mcp", mcp_api_key="test-key")
    )

    conn = connections[mcp_tools.MCP_SERVER_NAME]
    assert conn["url"] == "http://control-plane:8080/mcp"
    assert conn["headers"] == {mcp_tools.API_KEY_HEADER: "test-key"}


def test_server_name_matches_control_plane():
    # 서버 이름은 control-plane 의 spring.ai.mcp.server.name 과 일치 (관측·로그 대조용)
    assert mcp_tools.MCP_SERVER_NAME == "ops-control-plane"
