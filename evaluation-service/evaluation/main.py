"""evaluation-service FastAPI 엔트리 — 헬스 + Kafka 컨슈머 수명 (ADR-0019).

HTTP 표면은 헬스뿐이다. 일은 전부 `ops.analysis.results` 소비에서 시작한다 — 응답 경로 밖의 비동기 평가.
"""

import asyncio
from contextlib import asynccontextmanager, suppress

from fastapi import FastAPI

from evaluation.config import get_settings
from evaluation.config.logging_setup import configure_logging
from evaluation.config.otel import setup_telemetry
from evaluation.events.results_consumer import run_results_consumer

# uvicorn 이 이 모듈을 import 하는 시점에 실행 — lifespan 보다 앞서야 기동 로그부터 잡힌다
configure_logging()


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    # 전역 provider + httpx 계측은 컨슈머(재조회·Judge 호출)보다 앞서야 한다. import 시점이 아닌 여기서 읽는 이유:
    # Settings 는 AUTH_CLIENT_SECRET 필수라 모듈 import 만으로 조립하면 .env 없는 환경(CI)에서 깨진다
    setup_telemetry(settings.otel_exporter_otlp_endpoint)
    consumer_task = (
        asyncio.create_task(run_results_consumer(settings), name="kafka-results-consumer")
        if settings.kafka_bootstrap_servers
        else None
    )
    try:
        yield
    finally:
        if consumer_task is not None:
            consumer_task.cancel()
            with suppress(asyncio.CancelledError):
                await consumer_task


app = FastAPI(title="ai-ops-platform evaluation-service", lifespan=lifespan)


@app.get("/health")
def health() -> dict:
    settings = get_settings()
    return {
        "status": "ok",
        "sample_profile": settings.eval_sample_profile,
        "evidence_enabled": settings.eval_evidence_enabled,
        # 설정됨 ≠ 접속 성공 (agent-service 와 같은 한계)
        "kafka_enabled": bool(settings.kafka_bootstrap_servers),
        "otlp_enabled": bool(settings.otel_exporter_otlp_endpoint),
        "judge_prompt_version": settings.eval_judge_prompt_version,
    }
