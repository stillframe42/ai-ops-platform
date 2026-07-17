"""모니터링 에이전트 단독 실행 (실 LLM 호출) — DAY 9 확인 기준 검증용.

사용법:
1. `.env` 에 ANTHROPIC_API_KEY 설정
2. 스택 기동: `infra/` 에서 `docker compose up -d`
3. chaos 주입: `curl -X POST 'http://localhost:8080/chaos/latency?ms=4000'`
   (p95 상승까지 1~2분 대기 후 실행하면 요약에 수치가 잡힌다)
4. `uv run python -m scripts.run_monitor_agent` (agent-service 루트에서 — app 패키지 경로 확보)
5. 원복: `curl -X POST http://localhost:8080/chaos/reset`
"""

from datetime import UTC, datetime

from app.agents.monitor_agent import monitor_node
from app.supervisor.state import IncidentInfo


def main() -> None:
    incident = IncidentInfo(
        id=f"inc-manual-{datetime.now(UTC):%Y%m%d%H%M%S}",
        scenario="latency-surge",
        alert_name="TargetAppHighLatency",
        summary="p95 latency 3s 초과 (수동 트리거)",
        occurred_at=datetime.now(UTC).isoformat(),
    )
    update = monitor_node({"incident": incident, "messages": []})

    monitoring = update["monitoring"]
    print("=== 상황 요약 ===")
    print(monitoring.situation_summary)
    print("\n=== 근거 (실행한 쿼리) ===")
    for evidence in monitoring.evidences:
        print(f"- {evidence}")


if __name__ == "__main__":
    main()
