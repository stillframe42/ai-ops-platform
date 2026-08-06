"""A2A 개념 검증용 최소 에코 에이전트 서버 (Phase 5 오전 — 실험 브랜치 전용).

a2a-sdk 1.1.2 (스펙 v1.0) 기준 서버 구성 요소 확인이 목적:
- Agent Card: /.well-known/agent-card.json 으로 발견 (스킬·전송 바인딩·capabilities 선언)
- AgentExecutor: 태스크 수명주기(submitted → working → completed)를 EventQueue 로 발행
- FastAPI 마운트: add_a2a_routes_to_fastapi — 기존 앱과 병행 노출 가능 여부 확인

사용법: `uv run python -m scripts.a2a_sample.server` (agent-service 루트에서, 포트 9999)
"""

import uvicorn
from a2a.server.agent_execution import AgentExecutor, RequestContext
from a2a.server.events import EventQueue
from a2a.server.request_handlers import DefaultRequestHandler
from a2a.server.routes import add_a2a_routes_to_fastapi, create_agent_card_routes, create_jsonrpc_routes
from a2a.helpers import new_task_from_user_message
from a2a.server.tasks import InMemoryTaskStore, TaskUpdater
from a2a.types import AgentCapabilities, AgentCard, AgentInterface, AgentSkill, Part
from a2a.utils import TransportProtocol
from fastapi import FastAPI

HOST, PORT = "127.0.0.1", 9999


class EchoAgentExecutor(AgentExecutor):
    """받은 텍스트를 그대로 돌려주는 에이전트 — 태스크 수명주기 전이 관찰이 목적."""

    async def execute(self, context: RequestContext, event_queue: EventQueue) -> None:
        # 프레임워크 규약: 상태 업데이트 전에 Task 이벤트가 먼저 큐에 있어야 한다
        task = context.current_task
        if task is None:
            task = new_task_from_user_message(context.message)  # submitted 상태로 생성
            await event_queue.enqueue_event(task)
        updater = TaskUpdater(event_queue, task.id, task.context_id)
        await updater.start_work()  # TASK_STATE_WORKING — 처리 중 (스트리밍으로 관찰됨)
        text = context.get_user_input()
        await updater.add_artifact([Part(text=f"echo: {text}")], name="echo-result")
        await updater.complete()  # TASK_STATE_COMPLETED — 종결 (터미널 상태)

    async def cancel(self, context: RequestContext, event_queue: EventQueue) -> None:
        raise NotImplementedError("에코 태스크는 즉시 완료라 취소 경로 없음")


def build_agent_card() -> AgentCard:
    return AgentCard(
        name="echo-agent",
        description="A2A 개념 검증용 에코 에이전트",
        version="0.1.0",
        supported_interfaces=[
            AgentInterface(
                url=f"http://{HOST}:{PORT}/",
                protocol_binding=TransportProtocol.JSONRPC,
                protocol_version="1.0",
            )
        ],
        capabilities=AgentCapabilities(streaming=True),
        default_input_modes=["text/plain"],
        default_output_modes=["text/plain"],
        skills=[
            AgentSkill(
                id="echo",
                name="echo",
                description="입력 텍스트를 그대로 반환",
                tags=["demo"],
            )
        ],
    )


def build_app() -> FastAPI:
    card = build_agent_card()
    handler = DefaultRequestHandler(
        agent_executor=EchoAgentExecutor(), task_store=InMemoryTaskStore(), agent_card=card
    )
    app = FastAPI(title="a2a-echo-sample")
    add_a2a_routes_to_fastapi(
        app,
        agent_card_routes=create_agent_card_routes(card),
        jsonrpc_routes=create_jsonrpc_routes(handler, rpc_url="/"),
    )
    return app


if __name__ == "__main__":
    uvicorn.run(build_app(), host=HOST, port=PORT)
