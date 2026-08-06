"""A2A 클라이언트 — Agent Card 발견 + 태스크 위임 + 스트리밍 수신 (Phase 5 오전).

서버(server.py) 기동 후: `uv run python -m scripts.a2a_sample.client`
확인 대상: ① /.well-known/agent-card.json 조회 ② message/send 위임
③ StreamResponse 로 태스크 상태 전이(submitted → working → completed) 수신
"""

import asyncio
import uuid

import httpx
from a2a.client import A2ACardResolver, ClientConfig, create_client
from a2a.types import Message, Part, Role, SendMessageRequest, TaskState

SERVER_URL = "http://127.0.0.1:9999"


async def main() -> None:
    # 발견 단계를 명시적으로 밟는다 — well-known 경로에서 Agent Card 를 가져와 확인
    async with httpx.AsyncClient() as http:
        card = await A2ACardResolver(http, SERVER_URL).get_agent_card()
    print("=== Agent Card (발견) ===")
    print(f"name={card.name} / version={card.version}")
    print(f"interfaces={[(i.url, i.protocol_binding) for i in card.supported_interfaces]}")
    print(f"skills={[s.id for s in card.skills]} / streaming={card.capabilities.streaming}")

    client = await create_client(card, ClientConfig(streaming=True))
    request = SendMessageRequest(
        message=Message(
            message_id=str(uuid.uuid4()),
            role=Role.ROLE_USER,
            parts=[Part(text="안녕 A2A")],
        )
    )
    print("\n=== 태스크 위임 + 스트리밍 수신 ===")
    async for response in client.send_message(request):
        # StreamResponse 는 oneof: task / message / status_update / artifact_update
        kind = response.WhichOneof("payload")
        if kind == "task":
            print(f"[task] id={response.task.id} state={TaskState.Name(response.task.status.state)}")
        elif kind == "status_update":
            print(f"[status] {TaskState.Name(response.status_update.status.state)}")
        elif kind == "artifact_update":
            texts = [p.text for p in response.artifact_update.artifact.parts]
            print(f"[artifact] {response.artifact_update.artifact.name}: {texts}")
        elif kind == "message":
            print(f"[message] {[p.text for p in response.message.parts]}")


if __name__ == "__main__":
    asyncio.run(main())
