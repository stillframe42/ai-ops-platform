"""A2A 서버 단독 기동 (Phase 5 실측용 — 호스트 실행).

main.py 전체 앱(Kafka 컨슈머·체크포인터 포함) 대신 A2A 라우트만 띄운다 —
실측 중 Kafka 경로의 병행 인시던트(승인 카드 발송)를 피하기 위한 격리 실행.

사용법 (agent-service 루트, compose 관측 스택 기동 상태에서):
  uv run python -m scripts.run_a2a_server        # http://127.0.0.1:8010
"""

import uvicorn
from fastapi import FastAPI

from app.a2a.server import mount_a2a
from app.config.logging_setup import configure_logging

HOST, PORT = "127.0.0.1", 8010

configure_logging()
app = FastAPI(title="aiops-supervisor (A2A 실험)")
mount_a2a(app, base_url=f"http://{HOST}:{PORT}/")

if __name__ == "__main__":
    uvicorn.run(app, host=HOST, port=PORT)
