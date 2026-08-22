"""OTel 트레이스 전파 (Phase 6, DAY 34 서두 결정 ②) — exporter 미장착: 수집 백엔드는 9월.

범위는 전파와 상관까지다: httpx 계측이 나가는 요청(openai SDK 의 게이트웨이 호출 포함)에
W3C traceparent 를 주입하고, 인시던트 루트 스팬(runtime 몫)이 실행 1건의 호출 전부를 같은
traceId 로 묶는다. 스팬은 생성만 되고 어디로도 전송되지 않는다 — 전송 비용 0.
"""

import logging

from opentelemetry import trace
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider

logger = logging.getLogger(__name__)


def setup_tracing() -> None:
    """전역 TracerProvider 설정 + httpx 계측 — 멱등 (이미 SDK provider 면 재진입 무시).

    langfuse v4 SDK 도 OTel 기반이라 순서가 중요하다: 앱 기동 시 이 함수가 먼저 전역
    provider 를 잡는다 (Langfuse 핸들러 생성은 lifespan 안 — 이 함수 뒤).
    """
    if isinstance(trace.get_tracer_provider(), TracerProvider):
        return
    trace.set_tracer_provider(
        TracerProvider(resource=Resource.create({"service.name": "agent-service"}))
    )
    # 계측기 import 를 함수 안에서 — 비활성 경로(단위 테스트 대부분)에서 전역 패치를 만들지 않는다
    from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor

    HTTPXClientInstrumentor().instrument()
    logger.info("OTel 트레이스 전파 활성 — exporter 미장착 (수집 백엔드는 9월)")
