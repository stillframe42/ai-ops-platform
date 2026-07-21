"""전체 그래프 실행 (실 LLM 호출) — DAY 11 확인 기준 검증용.

Supervisor 라우팅 순서를 노드 실행 스트림으로 출력한다 — 시나리오별로
monitor → analysis → (P1/P2) action → 종료 순서가 맞는지 눈으로 확인한다.

사용법:
1. `.env` 에 ANTHROPIC_API_KEY 설정, 스택 기동 (`infra/` 에서 `docker compose up -d`)
2. chaos 주입 (시나리오별 — run_analysis_agent.py 의 안내와 동일)
3. `uv run python -m scripts.run_graph [시나리오]` (agent-service 루트에서)
   시나리오: memory-pressure(기본) | error-rate-surge | latency-surge
4. 원복: `curl -X POST http://localhost:8080/chaos/reset`
"""

import asyncio
import sys

from app.supervisor.graph import GRAPH_RECURSION_LIMIT, build_graph
from app.supervisor.runtime import build_incident


async def main() -> None:
    # async 인 이유: 에이전트 노드가 async (타임아웃 협조적 취소 전제, DAY 13) — sync stream 불가
    scenario = sys.argv[1] if len(sys.argv) > 1 else "memory-pressure"
    # 프리셋은 runtime 모듈로 일원화 (DAY 12) — POST /incidents/trigger 와 동일 인시던트 생성
    incident = build_incident(scenario)

    graph = build_graph()
    visited: list[str] = []
    final_state: dict = {}
    print(f"=== 그래프 실행: {scenario} ({incident.id}) ===")
    async for update in graph.astream(
        {"incident": incident, "messages": []},
        config={"recursion_limit": GRAPH_RECURSION_LIMIT},
        stream_mode="updates",
    ):
        for node, node_update in update.items():
            visited.append(node)
            final_state.update(node_update or {})
            if node == "supervisor":
                print(f"[supervisor] 결정: {node_update['supervisor_decision']} "
                      f"(방문 {node_update['supervisor_visits']}회)")
            else:
                print(f"→ {node} 실행 완료")

    print("\n=== 라우팅 순서 ===")
    print(" → ".join(visited))

    if (analysis := final_state.get("analysis")) is not None:
        print(f"\n분석: ({analysis.severity}, 확신도 {analysis.confidence}) "
              f"{analysis.root_cause_hypothesis}")
    if (plan := final_state.get("action")) is not None:
        print(f"조치 계획: {plan.actions}\n- 근거: {plan.rationale}")
        print(f"- 예상 효과: {plan.expected_effect}\n- 리스크: {plan.risk}")
    else:
        print("조치 계획: 없음 (P3 조기 종료 또는 미도달)")


if __name__ == "__main__":
    asyncio.run(main())
