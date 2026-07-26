"""그래프 실행 런타임 — 체크포인터 수명주기와 인시던트 단위 실행/재개/조회 (ADR-0009).

thread_id = incident.id: 인시던트가 곧 실행 단위다. 같은 thread_id 로 입력 None invoke 하면
마지막 체크포인트부터 이어가므로(Durable Execution), 완료된 노드의 LLM 호출은 반복되지 않는다.
"""

import asyncio
import logging
from contextlib import asynccontextmanager
from datetime import UTC, datetime

from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from langgraph.checkpoint.serde.jsonplus import JsonPlusSerializer
from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool

from app.config.settings import Settings
from app.config.tracing import build_langfuse_handler
from app.supervisor.graph import DONE, GRAPH_RECURSION_LIMIT, build_graph
from app.supervisor.state import (
    ActionPlan,
    AnalysisResult,
    IncidentInfo,
    MonitoringResult,
    NodeFailure,
    Scenario,
)

logger = logging.getLogger(__name__)

# 시나리오별 인시던트 프리셋 (Alert Rule 이름은 infra/prometheus/rules 기준)
INCIDENT_PRESETS: dict[str, tuple[str, str]] = {
    "memory-pressure": ("TargetAppHeapUsageHigh", "heap 사용률 85% 초과 (수동 트리거)"),
    "error-rate-surge": ("TargetAppHighErrorRate", "5xx 에러율 10% 초과 (수동 트리거)"),
    "latency-surge": ("TargetAppHighLatency", "p95 latency 3s 초과 (수동 트리거)"),
}


def build_checkpoint_serializer() -> JsonPlusSerializer:
    """체크포인트 직렬화기 — 상태 스키마의 pydantic 모델을 허용 목록에 명시 등록한다.

    기본(permissive)은 미등록 타입마다 "향후 차단 예정" 경고를 낸다 — 명시 등록으로
    경고를 없애고, 목록 밖 타입은 즉시 차단되므로 새 상태 모델의 등록 누락을 바로 잡을 수
    있다 (langgraph 업그레이드 대비, DAY 12 발견 후속). 안전 기본 타입(langchain 메시지 등)은
    별도 등록 없이 항상 허용된다.
    """
    return JsonPlusSerializer(
        allowed_msgpack_modules=[
            IncidentInfo,
            MonitoringResult,
            AnalysisResult,
            ActionPlan,
            NodeFailure,
        ]
    )


def is_run_complete(next_: tuple, values: dict) -> bool:
    """완주 판정 — next 없음 + supervisor 결정 done 둘 다 필요.

    next 만 보면 안 되는 이유 (DAY 14 E2E 실측): super-step 사이 과도기에 다음 태스크가
    아직 스케줄되지 않아 next 가 순간적으로 빈 튜플이 된다 — 실행 중인데 완주로 오판된다.
    방문 한도 강제 종료·에스컬레이션 종료도 supervisor 가 DONE 을 기록하므로 이 판정에 잡힌다.
    """
    return not next_ and values.get("supervisor_decision") == DONE


def build_incident(scenario: Scenario, incident_id: str | None = None) -> IncidentInfo:
    alert_name, summary = INCIDENT_PRESETS[scenario]
    now = datetime.now(UTC)
    return IncidentInfo(
        id=incident_id or f"inc-{scenario}-{now:%Y%m%d%H%M%S}",
        scenario=scenario,
        alert_name=alert_name,
        summary=summary,
        occurred_at=now.isoformat(),
    )


class GraphRuntime:
    """컴파일된 그래프 + 인시던트 단위 실행 관리. 백그라운드 태스크는 여기서 소유한다."""

    def __init__(self, graph, tracer=None) -> None:
        self.graph = graph
        self.tracer = tracer  # Langfuse 콜백 핸들러 — None 이면 트레이싱 비활성
        self._tasks: dict[str, asyncio.Task] = {}

    def _config(self, incident_id: str) -> dict:
        # scripts/run_graph.py 와 동일 관례 — recursion_limit 은 방문 카운터의 이중 방어
        config: dict = {
            "configurable": {"thread_id": incident_id},
            "recursion_limit": GRAPH_RECURSION_LIMIT,
        }
        if self.tracer is not None:
            # 세션 연결 규약: langfuse_session_id = thread_id — 인시던트 1건의
            # 전체 LLM 호출이 Langfuse 세션 하나로 묶인다 (비용 집계 단위)
            config["callbacks"] = [self.tracer]
            config["metadata"] = {"langfuse_session_id": incident_id}
        return config

    async def start(self, incident: IncidentInfo) -> None:
        await self.graph.ainvoke(
            {"incident": incident, "messages": []}, config=self._config(incident.id)
        )

    async def resume(self, incident_id: str) -> None:
        """입력 None + 동일 thread_id — 마지막 체크포인트의 미완 노드부터 이어간다."""
        await self.graph.ainvoke(None, config=self._config(incident_id))

    def start_background(self, incident: IncidentInfo) -> None:
        self._spawn(incident.id, self.start(incident))

    def resume_background(self, incident_id: str) -> None:
        self._spawn(incident_id, self.resume(incident_id))

    def _spawn(self, incident_id: str, coro) -> None:
        task = asyncio.create_task(coro, name=f"incident:{incident_id}")
        self._tasks[incident_id] = task
        task.add_done_callback(lambda t: self._log_done(incident_id, t))

    @staticmethod
    def _log_done(incident_id: str, task: asyncio.Task) -> None:
        # 백그라운드 실패를 삼키지 않는다 — 체크포인트는 남아 있으므로 resume 으로 재개 가능
        if not task.cancelled() and task.exception() is not None:
            logger.error(
                "인시던트 %s 실행 중단: %r — POST /incidents/%s/resume 로 재개 가능",
                incident_id,
                task.exception(),
                incident_id,
            )

    async def get_state(self, incident_id: str) -> dict | None:
        snapshot = await self.graph.aget_state(self._config(incident_id))
        if not snapshot.values:
            return None  # 체크포인트 없음 — 모르는 인시던트
        values = snapshot.values
        return {
            "incident_id": incident_id,
            "next": list(snapshot.next),
            "done": is_run_complete(snapshot.next, values),
            "supervisor_decision": values.get("supervisor_decision"),
            "supervisor_visits": values.get("supervisor_visits", 0),
            "completed": {
                field: values.get(field) is not None
                for field in ("monitoring", "analysis", "action")
            },
            # 노드 실패 기록 (DAY 13) — error_handler 가 남긴 NodeFailure 를 dict 로 직렬화
            "errors": [failure.model_dump() for failure in values.get("errors") or []],
            # error_handler 밖에서 죽은 미완 태스크의 중단 원인 (예: 핸들러 없는 노드)
            "pending_errors": [
                {"node": task.name, "error": repr(task.error)}
                for task in snapshot.tasks
                if task.error is not None
            ],
        }

    async def get_result(self, incident_id: str) -> dict | None:
        """ops.analysis.results 발행 페이로드 (DAY 18) — 보고서 = monitoring/analysis/action 합성.

        status 는 completed/partial 2분류: 완주했고 실패 기록이 없어야 completed.
        부분 보고서(DAY 13)도 발행 대상이라 — 실패 종료를 침묵시키지 않는다.
        """
        snapshot = await self.graph.aget_state(self._config(incident_id))
        if not snapshot.values:
            return None  # 체크포인트 없음 — 모르는 인시던트
        values = snapshot.values
        errors = [failure.model_dump() for failure in values.get("errors") or []]
        pending_errors = [
            {"node": task.name, "error": repr(task.error)}
            for task in snapshot.tasks
            if task.error is not None
        ]
        done = is_run_complete(snapshot.next, values)

        def dump(field: str) -> dict | None:
            model = values.get(field)
            return model.model_dump() if model is not None else None

        incident: IncidentInfo = values["incident"]
        return {
            "incident_id": incident_id,
            "scenario": incident.scenario,
            "alert_name": incident.alert_name,
            "status": "completed" if done and not errors and not pending_errors else "partial",
            "monitoring": dump("monitoring"),
            "analysis": dump("analysis"),
            "action": dump("action"),
            "errors": errors,
            "pending_errors": pending_errors,
            "supervisor_visits": values.get("supervisor_visits", 0),
            "completed_at": datetime.now(UTC).isoformat(),
        }

    async def get_history(self, incident_id: str) -> list[dict]:
        """체크포인트 히스토리 — 최신이 먼저 온다 (aget_state_history 순서 그대로)."""
        history = []
        async for snapshot in self.graph.aget_state_history(self._config(incident_id)):
            history.append(
                {
                    "step": (snapshot.metadata or {}).get("step"),
                    "next": list(snapshot.next),
                    "created_at": snapshot.created_at,
                }
            )
        return history


@asynccontextmanager
async def open_runtime(settings: Settings):
    """앱 수명 동안 체크포인터 연결을 열고 그래프를 조립한다. setup() 은 멱등 — 매 기동 호출.

    단일 커넥션(from_conn_string)이 아닌 커넥션 풀을 쓴다 — DAY 12 실측에서 postgres
    재기동 시 단일 커넥션이 영구히 죽어 전 API 가 500 이 됐다. check 로 대여 시점에
    죽은 커넥션을 걸러내 재연결하므로 DB 재기동에서 자동 복구된다 (DAY 13).
    """
    if not settings.checkpoint_db_url:
        raise ValueError(
            "CHECKPOINT_DB_URL 이 없습니다 — Durable Execution 은 PostgreSQL 전제 (ADR-0009)"
        )
    async with AsyncConnectionPool(
        settings.checkpoint_db_url,
        min_size=1,
        max_size=4,
        # from_conn_string 이 쓰는 커넥션 설정과 동일 (autocommit 은 setup() 마이그레이션 전제)
        kwargs={"autocommit": True, "prepare_threshold": 0, "row_factory": dict_row},
        check=AsyncConnectionPool.check_connection,
        open=False,  # 열기는 async with 진입 시점 — 생성자 open 은 deprecated
    ) as pool:
        checkpointer = AsyncPostgresSaver(pool, serde=build_checkpoint_serializer())
        await checkpointer.setup()
        tracer = build_langfuse_handler(settings)  # 키 없으면 None — 트레이싱 비활성
        yield GraphRuntime(build_graph(checkpointer=checkpointer), tracer=tracer)
