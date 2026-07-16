"""agent-service FastAPI 엔트리.

인시던트 트리거(POST /incidents/trigger)와 상태 조회 API 는 DAY 12(체크포인터·thread_id)에서 추가한다.
"""

from fastapi import FastAPI

from app.config import get_settings

app = FastAPI(title="ai-ops-platform agent-service")


@app.get("/health")
def health() -> dict:
    settings = get_settings()
    return {
        "status": "ok",
        "llm_model": settings.llm_model,
        "prometheus_url": settings.prometheus_url,
    }
