"""앱 로거 출력 설정 (DAY 21) — uvicorn 기본 로깅은 uvicorn.* 로거에만 핸들러를 붙이고
root 로거는 건드리지 않아, app.* 의 INFO 로그가 어디에도 출력되지 않았다 (lastResort 는
WARNING 이상만 stderr). 장애 검증에서 "미완 체크포인트 재개" 로그 침묵으로 실증된
문제 — root 에 stdout 핸들러를 달아 docker logs 로 보낸다.

uvicorn.* 로거는 propagate=False 라 root 핸들러와 중복 출력되지 않는다.
"""

import logging
import os
import sys

from opentelemetry import trace


class _TraceContextFilter(logging.Filter):
    """현재 스팬의 traceId 를 로그 레코드에 붙인다 — 게이트웨이 로그와의 상관 키.

    스팬 밖(기동·컨슈머 루프 등)이나 SDK 미구성이면 "-" — 형식은 유지된다.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        context = trace.get_current_span().get_span_context()
        record.trace_id = f"{context.trace_id:032x}" if context.is_valid else "-"
        return True


def configure_logging() -> None:
    root = logging.getLogger()
    # --log-config 등 외부에서 이미 구성했으면 덮어쓰지 않는다 (멱등 — 재호출 안전)
    if root.handlers:
        return
    handler = logging.StreamHandler(sys.stdout)
    handler.addFilter(_TraceContextFilter())
    # 타임스탬프 포함 — 인시던트 구간 시간 측정이 로그 시각에 의존한다 (실측 관례).
    # [trace_id] 는 게이트웨이 로그의 [traceId-spanId] 와 같은 자리
    handler.setFormatter(
        logging.Formatter("%(asctime)s %(levelname)s %(name)s [%(trace_id)s] %(message)s")
    )
    root.addHandler(handler)
    root.setLevel(os.getenv("LOG_LEVEL", "INFO").upper())
