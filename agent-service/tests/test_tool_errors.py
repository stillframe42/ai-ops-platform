"""ToolErrorFeedback 미들웨어 — 비일시적 도구 예외는 모델 피드백(ToolMessage error), 일시적 예외는 전파.

실 에이전트 루프 대신 미들웨어 계약만 검증한다 — handler 를 직접 주입.
"""

import asyncio
from types import SimpleNamespace

import httpx
import pytest
from langchain_core.messages import ToolMessage

from app.agents.tool_errors import MAX_ERROR_CHARS, ToolErrorFeedback


def _request(name: str = "query_prometheus") -> SimpleNamespace:
    return SimpleNamespace(tool_call={"id": "call-1", "name": name, "args": {"promql": "x"}}, tool=None)


def test_whitelist_rejection_becomes_error_tool_message():
    middleware = ToolErrorFeedback()

    def handler(request):
        raise ValueError("허용되지 않은 메트릭: http_client_requests_seconds_count")

    message = middleware.wrap_tool_call(_request(), handler)

    assert isinstance(message, ToolMessage)
    assert message.status == "error" and message.tool_call_id == "call-1" and message.name == "query_prometheus"
    assert "허용되지 않은 메트릭: http_client_requests_seconds_count" in message.content
    assert "다시 시도" in message.content


def test_async_path_has_same_contract():
    middleware = ToolErrorFeedback()

    async def handler(request):
        raise ValueError("허용되지 않은 로그 레벨: TRACE")

    message = asyncio.run(middleware.awrap_tool_call(_request("get_app_logs"), handler))

    assert message.status == "error" and "TRACE" in message.content


def test_transient_errors_propagate_for_node_retry():
    middleware = ToolErrorFeedback()

    def handler(request):
        raise ConnectionError("[스텁] Prometheus 순단")

    with pytest.raises(ConnectionError):
        middleware.wrap_tool_call(_request(), handler)

    async def handler_5xx(request):
        response = httpx.Response(503, request=httpx.Request("GET", "http://prometheus/api/v1/query"))
        raise httpx.HTTPStatusError("503", request=response.request, response=response)

    with pytest.raises(httpx.HTTPStatusError):
        asyncio.run(middleware.awrap_tool_call(_request(), handler_5xx))


def test_prometheus_4xx_is_fed_back_not_retried():
    """잘못된 질의로 받은 400 은 요청 자체의 문제 — 재시도가 아니라 모델이 질의를 고쳐야 한다."""
    middleware = ToolErrorFeedback()

    def handler(request):
        response = httpx.Response(400, request=httpx.Request("GET", "http://prometheus/api/v1/query"))
        raise httpx.HTTPStatusError("400 Bad Request", request=response.request, response=response)

    message = middleware.wrap_tool_call(_request(), handler)
    assert message.status == "error" and "HTTPStatusError" in message.content


def test_success_passes_through_and_long_errors_are_truncated():
    middleware = ToolErrorFeedback()
    ok = ToolMessage(content="ok", tool_call_id="call-1")
    assert middleware.wrap_tool_call(_request(), lambda request: ok) is ok

    def handler(request):
        raise ValueError("x" * (MAX_ERROR_CHARS + 500))

    message = middleware.wrap_tool_call(_request(), handler)
    assert len(message.content) < MAX_ERROR_CHARS + 200
