"""A2A 클라이언트 — aiops-supervisor 에 인시던트 분석 태스크 위임 (Phase 5 실측).

발견(Agent Card) → 위임(message/send) → 상태 스트리밍 → 분석 보고서 아티팩트 수신.

사용법: `uv run python -m scripts.run_a2a_client [시나리오]` (기본 error-rate-surge)
"""

import asyncio
import sys
import uuid

import httpx
from a2a.client import A2ACardResolver, ClientConfig, create_client
from a2a.types import Message, Part, Role, SendMessageRequest, TaskState

SERVER_URL = "http://127.0.0.1:8010"


async def main() -> None:
    scenario = sys.argv[1] if len(sys.argv) > 1 else "error-rate-surge"

    async with httpx.AsyncClient() as http:
        card = await A2ACardResolver(http, SERVER_URL).get_agent_card()
    print("=== Agent Card (발견) ===")
    print(f"name={card.name} / version={card.version}")
    skill = card.skills[0]
    print(f"skill={skill.id} — {skill.description}")
    print(f"streaming={card.capabilities.streaming}")

    # LLM 분석은 수 분 단위 — 스트리밍 수신 타임아웃을 넉넉히 잡는다 (분석 노드 예산 180s 기준)
    client = await create_client(
        card,
        ClientConfig(
            streaming=True,
            httpx_client=httpx.AsyncClient(timeout=httpx.Timeout(10.0, read=300.0)),
        ),
    )
    request = SendMessageRequest(
        message=Message(
            message_id=str(uuid.uuid4()),
            role=Role.ROLE_USER,
            parts=[Part(text=scenario)],
        )
    )
    print(f"\n=== 태스크 위임: {scenario} ===")
    async for response in client.send_message(request):
        kind = response.WhichOneof("payload")
        if kind == "task":
            print(f"[task] id={response.task.id} state={TaskState.Name(response.task.status.state)}")
        elif kind == "status_update":
            status = response.status_update.status
            note = "".join(p.text for p in status.message.parts) if status.HasField("message") else ""
            print(f"[status] {TaskState.Name(status.state)} {note}")
        elif kind == "artifact_update":
            artifact = response.artifact_update.artifact
            print(f"[artifact] {artifact.name}:")
            for part in artifact.parts:
                print(part.text)


if __name__ == "__main__":
    asyncio.run(main())
