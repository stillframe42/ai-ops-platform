"""일시적 오류 판정 — 노드 재시도 정책(graph)과 도구 오류 미들웨어(agents)가 같은 기준을 쓴다.

graph.py 에서 분리한 이유 (DAY 46): agents → graph 는 순환 import (graph 가 노드 함수를 가져온다).
"""

import httpx
import openai
from langgraph.errors import NodeTimeoutError


def retry_on_transient(exc: Exception) -> bool:
    """일시적 오류만 재시도한다 — 허용 목록 방식.

    langgraph 기본 정책(default_retry_on)은 "모르는 예외는 재시도"라서 NodeTimeoutError 도
    재시도 대상이 된다 — 타임아웃 재시도는 대기를 반복할 뿐이라 (분석 180s × 3회) 명시적으로
    제외한다. 프로그래밍 오류(ValueError 등)는 재시도해도 결과가 같으므로 목록에 없다.
    """
    if isinstance(exc, NodeTimeoutError):
        return False
    # MCP Streamable HTTP 의 연결 실패는 anyio TaskGroup 이 ExceptionGroup 으로 감싸서
    # 전파된다 (DAY 16 실측: ExceptionGroup(ConnectError)) — 풀어서 말단까지 판정한다.
    # 전원 일시적일 때만 재시도 — 비일시적 오류가 섞였으면 재시도해도 결과가 같다
    if isinstance(exc, BaseExceptionGroup):
        return all(
            isinstance(sub, Exception) and retry_on_transient(sub) for sub in exc.exceptions
        )
    if isinstance(exc, httpx.HTTPStatusError):
        return exc.response.status_code >= 500  # 서버 측 오류만 — 4xx 는 요청 자체의 문제
    # LLM 게이트웨이 호출 실패는 openai SDK 예외로 전파된다 (httpx 계열 아님 — 실측).
    # 연결 실패(순단·pod 교체)와 5xx·429(Retry-After) 만 — 4xx 는 요청 자체의 문제
    if isinstance(exc, openai.APIConnectionError):
        return True
    if isinstance(exc, openai.APIStatusError):
        return exc.status_code >= 500 or exc.status_code == 429
    return isinstance(exc, (ConnectionError, httpx.RequestError))
