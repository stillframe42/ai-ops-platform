"""도구 오류를 모델에 되돌려주는 미들웨어 (DAY 46) — 노드 실패 대신 재시도 기회.

langchain 1.x `create_agent` 는 도구 실행 예외를 그대로 전파한다 (`AgentMiddleware.wrap_tool_call` 문서:
"Exceptions propagate unless handle_tool_errors is configured"). 그래서 LLM 이 화이트리스트 밖 메트릭으로 PromQL 을
한 번 잘못 쓰면(RT-14 ValueError) 분석 노드 전체가 실패해 partial 보고서가 됐다 — kind 최근 11건 중 3건 (2026-09-10 실측:
잘못된 PromQL 2 · Prometheus 400 1). 검증기는 그대로 두고(전송 차단 유지), 예외를 `status="error"` ToolMessage 로 바꿔
모델이 다른 질의로 다시 시도하게 한다.

일시적 오류(연결 실패·5xx·429)는 되돌려주지 않고 전파한다 — 노드 재시도 정책(`retry_on_transient`)이 그 몫이고,
모델에게 "다시 불러라" 라고 해 봐야 같은 인프라 장애를 만난다.
"""

import logging

from langchain.agents.middleware import AgentMiddleware, ToolCallRequest
from langchain_core.messages import ToolMessage

from app.supervisor.transient import retry_on_transient

logger = logging.getLogger(__name__)

# 오류 문장 상한 — 예외 메시지에 허용 목록 전체가 실리는 경우(검증기)가 있어 토큰 보호
MAX_ERROR_CHARS = 600


def error_feedback(tool_name: str, exc: Exception) -> str:
    """모델이 읽는 오류 문장 — 무엇이 거부됐는지와 다음 행동을 함께 준다."""
    detail = str(exc)[:MAX_ERROR_CHARS]
    return f"도구 {tool_name} 호출 실패 ({type(exc).__name__}): {detail}\n인자를 바꿔 다시 시도하거나 다른 도구를 사용하라."


class ToolErrorFeedback(AgentMiddleware):
    """비일시적 도구 예외 → ToolMessage(status=error). 일시적 예외는 전파 (노드 재시도 정책 몫)."""

    def _to_message(self, request: ToolCallRequest, exc: Exception) -> ToolMessage:
        name = request.tool_call.get("name", "?")
        logger.warning("도구 오류를 모델 피드백으로 전환 — %s: %r", name, exc)
        return ToolMessage(
            content=error_feedback(name, exc),
            tool_call_id=request.tool_call["id"],
            name=name,
            status="error",
        )

    def wrap_tool_call(self, request: ToolCallRequest, handler):
        try:
            return handler(request)
        except Exception as exc:  # noqa: BLE001 — 일시적 여부는 아래에서 판정
            if retry_on_transient(exc):
                raise
            return self._to_message(request, exc)

    async def awrap_tool_call(self, request: ToolCallRequest, handler):
        try:
            return await handler(request)
        except Exception as exc:  # noqa: BLE001
            if retry_on_transient(exc):
                raise
            return self._to_message(request, exc)
