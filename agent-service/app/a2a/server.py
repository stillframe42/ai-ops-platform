"""Supervisor 를 A2A 서버로 노출하는 어댑터 (Phase 5 실험 — feature/a2a-experiment 전용).

기존 FastAPI·Kafka 경로와 병행 노출이며 대체가 아니다 (weekly-plan Phase 5).
실험 범위는 분석 위임 1건: A2A 클라이언트가 incident-analysis 스킬로 시나리오를 위임하면
monitor → analysis 노드를 순차 실행하고, 진행 상태를 태스크 수명주기로 스트리밍한다.

경계 메모 (ADR-0012 소재):
- 조치(action)·승인(approval)은 위임 범위 밖 — 승인 대기는 A2A 로는 `input-required` 상태에
  대응하지만, 이 플랫폼의 승인은 Slack·감사 기록과 결합한 도메인 상태라 실험 범위에서 제외.
- 그래프(supervisor 순환)를 재사용하지 않고 노드 2개를 직접 호출한다 — A2A 태스크는
  체크포인터 없는 1회 실행이라 Durable Execution(오프셋 층 무유실)과 결이 다르다.
"""

import json

from a2a.helpers import new_task_from_user_message
from a2a.server.agent_execution import AgentExecutor, RequestContext
from a2a.server.events import EventQueue
from a2a.server.request_handlers import DefaultRequestHandler
from a2a.server.routes import (
    add_a2a_routes_to_fastapi,
    create_agent_card_routes,
    create_jsonrpc_routes,
)
from a2a.server.tasks import InMemoryTaskStore, TaskUpdater
from a2a.types import (
    AgentCapabilities,
    AgentCard,
    AgentInterface,
    AgentSkill,
    Part,
    TaskState,
)
from a2a.utils import TransportProtocol
from fastapi import FastAPI

from app.agents.analysis_agent import analysis_node
from app.agents.monitor_agent import monitor_node
from app.supervisor.runtime import INCIDENT_PRESETS, build_incident

AGENT_NAME = "aiops-supervisor"
SKILL_ID = "incident-analysis"


class IncidentAnalysisExecutor(AgentExecutor):
    """분석 위임 실행기 — 입력 텍스트(시나리오명)를 인시던트 분석 보고서 아티팩트로 바꾼다."""

    async def execute(self, context: RequestContext, event_queue: EventQueue) -> None:
        # 프레임워크 규약: 상태 업데이트 전에 Task 이벤트가 먼저 큐에 있어야 한다
        task = context.current_task
        if task is None:
            task = new_task_from_user_message(context.message)
            await event_queue.enqueue_event(task)
        updater = TaskUpdater(event_queue, task.id, task.context_id)

        scenario = (context.get_user_input() or "").strip() or "error-rate-surge"
        if scenario not in INCIDENT_PRESETS:
            # 화이트리스트 밖 요청은 rejected 종결 — 조치 카탈로그와 같은 원칙 (scenarios.md)
            await updater.reject(
                updater.new_agent_message(
                    [Part(text=f"지원하지 않는 시나리오: {scenario} (지원: {sorted(INCIDENT_PRESETS)})")]
                )
            )
            return

        incident = build_incident(scenario)
        await updater.start_work(
            updater.new_agent_message([Part(text=f"{incident.id}: 모니터링 수집 시작")])
        )
        monitor_update = await monitor_node({"incident": incident, "messages": []})
        monitoring = monitor_update["monitoring"]

        await updater.update_status(
            TaskState.TASK_STATE_WORKING,
            updater.new_agent_message([Part(text="상황 요약 확보 — 원인 분석 진행")]),
        )
        analysis_update = await analysis_node(
            {"incident": incident, "monitoring": monitoring, "messages": []}
        )
        analysis = analysis_update["analysis"]

        report = {
            "incident_id": incident.id,
            "scenario": incident.scenario,
            "alert_name": incident.alert_name,
            "situation_summary": monitoring.situation_summary,
            "analysis": analysis.model_dump(),
        }
        await updater.add_artifact(
            [Part(text=json.dumps(report, ensure_ascii=False, indent=2), media_type="application/json")],
            name="analysis-report",
        )
        await updater.complete()

    async def cancel(self, context: RequestContext, event_queue: EventQueue) -> None:
        # 협조적 취소는 실험 범위 밖 — 노드 타임아웃(그래프 경로)과 달리 여기선 미지원 명시
        raise NotImplementedError("incident-analysis 태스크는 취소 미지원 (실험 범위 밖)")


def build_agent_card(base_url: str) -> AgentCard:
    return AgentCard(
        name=AGENT_NAME,
        description="AIOps 멀티 에이전트 Supervisor — 인시던트 분석 위임을 A2A 로 수신",
        version="0.1.0",
        supported_interfaces=[
            AgentInterface(
                url=base_url,
                protocol_binding=TransportProtocol.JSONRPC,
                protocol_version="1.0",
            )
        ],
        capabilities=AgentCapabilities(streaming=True),
        default_input_modes=["text/plain"],
        default_output_modes=["application/json"],
        skills=[
            AgentSkill(
                id=SKILL_ID,
                name="인시던트 분석",
                description=(
                    "시나리오명(latency-surge | error-rate-surge | memory-pressure)을 받아 "
                    "메트릭 수집·원인 분석 보고서(JSON)를 반환"
                ),
                tags=["aiops", "analysis"],
                examples=sorted(INCIDENT_PRESETS),
            )
        ],
    )


def mount_a2a(app: FastAPI, base_url: str) -> None:
    """기존 FastAPI 앱에 A2A 라우트를 병행 마운트한다 — 카드는 well-known, RPC 는 루트 POST."""
    card = build_agent_card(base_url)
    handler = DefaultRequestHandler(
        agent_executor=IncidentAnalysisExecutor(),
        task_store=InMemoryTaskStore(),
        agent_card=card,
    )
    add_a2a_routes_to_fastapi(
        app,
        agent_card_routes=create_agent_card_routes(card),
        jsonrpc_routes=create_jsonrpc_routes(handler, rpc_url="/"),
    )
