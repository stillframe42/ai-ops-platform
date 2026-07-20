"""그래프 실행 런타임 — 체크포인터 수명주기와 인시던트 단위 실행/재개/조회 (ADR-0009).

thread_id = incident.id: 인시던트가 곧 실행 단위다. 같은 thread_id 로 입력 None invoke 하면
마지막 체크포인트부터 이어가므로(Durable Execution), 완료된 노드의 LLM 호출은 반복되지 않는다.
"""

import asyncio
import logging
from contextlib import asynccontextmanager
from datetime import UTC, datetime

from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver

from app.config.settings import Settings
from app.supervisor.graph import GRAPH_RECURSION_LIMIT, build_graph
from app.supervisor.state import IncidentInfo, Scenario

logger = logging.getLogger(__name__)

# 시나리오별 인시던트 프리셋 (Alert Rule 이름은 infra/prometheus/rules 기준)
INCIDENT_PRESETS: dict[str, tuple[str, str]] = {
    "memory-pressure": ("TargetAppHeapUsageHigh", "heap 사용률 85% 초과 (수동 트리거)"),
    "error-rate-surge": ("TargetAppHighErrorRate", "5xx 에러율 10% 초과 (수동 트리거)"),
    "latency-surge": ("TargetAppHighLatency", "p95 latency 3s 초과 (수동 트리거)"),
}


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

    def __init__(self, graph) -> None:
        self.graph = graph
        self._tasks: dict[str, asyncio.Task] = {}

    @staticmethod
    def _config(incident_id: str) -> dict:
        # scripts/run_graph.py 와 동일 관례 — recursion_limit 은 방문 카운터의 이중 방어
        return {
            "configurable": {"thread_id": incident_id},
            "recursion_limit": GRAPH_RECURSION_LIMIT,
        }

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
            "done": not snapshot.next,
            "supervisor_decision": values.get("supervisor_decision"),
            "supervisor_visits": values.get("supervisor_visits", 0),
            "completed": {
                field: values.get(field) is not None
                for field in ("monitoring", "analysis", "action")
            },
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
    """앱 수명 동안 체크포인터 연결을 열고 그래프를 조립한다. setup() 은 멱등 — 매 기동 호출."""
    if not settings.checkpoint_db_url:
        raise ValueError(
            "CHECKPOINT_DB_URL 이 없습니다 — Durable Execution 은 PostgreSQL 전제 (ADR-0009)"
        )
    async with AsyncPostgresSaver.from_conn_string(settings.checkpoint_db_url) as checkpointer:
        await checkpointer.setup()
        yield GraphRuntime(build_graph(checkpointer=checkpointer))
