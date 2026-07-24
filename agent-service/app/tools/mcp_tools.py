"""MCP 클라이언트 — control-plane 도구 서버의 도구를 발견해 LangChain 도구로 변환 (ADR-0010).

도구 이름·스키마는 여기에 없다 — tools/list 응답이 유일한 출처라서 control-plane 에
도구가 추가돼도 이 모듈은 무수정이다 (스키마 서버 단일 관리).

수명주기 (0.3.0 실측): MultiServerMCPClient 는 영속 연결을 유지하지 않는다 — 클라이언트
객체는 설정 홀더이고, get_tools() 의 발견 1왕복과 이후 각 도구 호출이 전부 독립 HTTP
세션이다 ("A new session will be created for each tool call"). 따라서 재사용할 연결이
없고, 발견 결과의 캐시 정책만 호출자(분석 에이전트) 몫이다.
"""

from langchain_core.tools import BaseTool
from langchain_mcp_adapters.client import MultiServerMCPClient

from app.config.settings import Settings

# control-plane 의 spring.ai.mcp.server.name 과 일치 — 관측·로그 대조용 식별자
MCP_SERVER_NAME = "ops-control-plane"

# API Key 헤더 이름 — control-plane 의 McpApiKeyFilter 와 계약
API_KEY_HEADER = "X-API-Key"


def build_mcp_connections(settings: Settings) -> dict:
    """Streamable HTTP 연결 구성. 키 미설정이면 헤더 자체를 생략한다 — 서버 필터도
    키 미설정이면 인증을 생략하므로 로컬 개발에서 양쪽 무설정으로 동작한다 (키-게이트 관례)."""
    connection: dict = {"transport": "streamable_http", "url": settings.mcp_server_url}
    if settings.mcp_api_key:
        connection["headers"] = {API_KEY_HEADER: settings.mcp_api_key}
    return {MCP_SERVER_NAME: connection}


async def load_mcp_tools(settings: Settings) -> list[BaseTool]:
    """tools/list 1왕복으로 도구를 발견한다. 도구 호출 오류는 어댑터 기본값
    (handle_tool_errors=True)대로 오류 ToolMessage 로 LLM 에 돌아간다 — 예외 비전파
    (DAY 13 부분 진행 관례와 정합)."""
    client = MultiServerMCPClient(build_mcp_connections(settings))
    return await client.get_tools()
