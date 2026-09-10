"""앱 로거 출력 설정 — agent-service `logging_setup.py` 와 동일 규약 (uvicorn 은 root 로거를 건드리지 않아
app.* 의 INFO 가 어디에도 출력되지 않는다). 샘플링 결정 로그가 실측 근거이므로 stdout 으로 보낸다.
"""

import logging
import os
import sys

from opentelemetry import trace


class _TraceContextFilter(logging.Filter):
    """현재 스팬의 traceId 를 로그 레코드에 붙인다 — 인시던트 trace 와의 상관 키. 스팬 밖이면 "-"."""

    def filter(self, record: logging.LogRecord) -> bool:
        context = trace.get_current_span().get_span_context()
        record.trace_id = f"{context.trace_id:032x}" if context.is_valid else "-"
        return True


def configure_logging() -> None:
    root = logging.getLogger()
    # 외부에서 이미 구성했으면 덮어쓰지 않는다 (멱등)
    if root.handlers:
        return
    handler = logging.StreamHandler(sys.stdout)
    handler.addFilter(_TraceContextFilter())
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s [%(trace_id)s] %(message)s"))
    root.addHandler(handler)
    root.setLevel(os.getenv("LOG_LEVEL", "INFO").upper())
    # httpx 는 요청마다 INFO 한 줄 — 평가 1건 = 재조회 6요청이라 결정 로그가 묻힌다 (2026-09-10 compose 실측). 실패는 WARNING 이상으로 남는다
    logging.getLogger("httpx").setLevel(logging.WARNING)
