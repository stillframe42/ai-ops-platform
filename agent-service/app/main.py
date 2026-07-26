"""agent-service FastAPI 엔트리 — 인시던트 트리거/재개/상태 조회 (Durable Execution, ADR-0009).

트리거는 202 + 백그라운드 실행: 그래프 완주는 수 분 단위(LLM·도구 호출)라 동기 응답이 불가하고,
"실행 중 강제 종료 → 재개" 데모도 실행 중 상태를 전제한다. 진행은 상태/히스토리 API 로 관찰한다.
"""

import asyncio
from contextlib import asynccontextmanager, suppress
from typing import Literal

from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel

from app.config import get_settings
from app.events.incident_consumer import run_incident_consumer
from app.supervisor.runtime import build_incident, open_runtime


@asynccontextmanager
async def lifespan(app: FastAPI):
    # 체크포인터 수명 = 앱 수명 — 연결을 열고 setup(멱등) 후 그래프를 조립한다
    settings = get_settings()
    async with open_runtime(settings) as runtime:
        app.state.runtime = runtime
        # Kafka 인시던트 컨슈머 (DAY 18, ADR-0011) — 빈 bootstrap 이면 비활성 (수동 트리거만).
        # 접속 실패는 컨슈머 안에서 백오프 재시도 — 앱 기동을 막지 않는다
        consumer_task = (
            asyncio.create_task(
                run_incident_consumer(settings, runtime), name="kafka-incident-consumer"
            )
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


app = FastAPI(title="ai-ops-platform agent-service", lifespan=lifespan)


class TriggerRequest(BaseModel):
    # "unknown" 은 Alert payload 정규화 실패 대비 값 — 수동 트리거로는 주입 불가
    scenario: Literal["latency-surge", "error-rate-surge", "memory-pressure"]


@app.get("/health")
def health() -> dict:
    settings = get_settings()
    return {
        "status": "ok",
        "llm_model": settings.llm_model,
        "prometheus_url": settings.prometheus_url,
        # 트레이싱 활성 여부 — 키 존재만 노출 (키 값은 절대 노출하지 않는다)
        "langfuse_enabled": bool(
            settings.langfuse_host
            and settings.langfuse_public_key
            and settings.langfuse_secret_key
        ),
        # Kafka 컨슈머 활성 여부 (DAY 18) — 설정됨 ≠ 접속 성공 (langfuse_enabled 와 같은 한계)
        "kafka_enabled": bool(settings.kafka_bootstrap_servers),
    }


@app.post("/incidents/trigger", status_code=202)
async def trigger_incident(body: TriggerRequest, request: Request) -> dict:
    incident = build_incident(body.scenario)
    request.app.state.runtime.start_background(incident)
    # incident_id 가 곧 thread_id — 이후 재개/조회의 키
    return {
        "incident_id": incident.id,
        "scenario": incident.scenario,
        "alert_name": incident.alert_name,
    }


@app.post("/incidents/{incident_id}/resume", status_code=202)
async def resume_incident(incident_id: str, request: Request) -> dict:
    runtime = request.app.state.runtime
    state = await runtime.get_state(incident_id)
    if state is None:
        raise HTTPException(status_code=404, detail="체크포인트가 없는 인시던트")
    if state["done"]:
        raise HTTPException(status_code=409, detail="이미 종료된 인시던트 — 재개할 중단 지점이 없다")
    runtime.resume_background(incident_id)
    return {"incident_id": incident_id, "resumed_from": state["next"]}


@app.get("/incidents/{incident_id}/state")
async def incident_state(incident_id: str, request: Request) -> dict:
    state = await request.app.state.runtime.get_state(incident_id)
    if state is None:
        raise HTTPException(status_code=404, detail="체크포인트가 없는 인시던트")
    return state


@app.get("/incidents/{incident_id}/history")
async def incident_history(incident_id: str, request: Request) -> dict:
    history = await request.app.state.runtime.get_history(incident_id)
    if not history:
        raise HTTPException(status_code=404, detail="체크포인트가 없는 인시던트")
    return {"incident_id": incident_id, "checkpoints": history}
