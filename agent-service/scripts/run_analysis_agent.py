"""분석 에이전트 단독 실행 (실 LLM 호출) — DAY 10 확인 기준 검증용.

monitor → analysis 를 순서대로 실행한다 — 분석 입력(모니터링 요약)을 실제 값으로 쓰기 위해.

사용법:
1. `.env` 에 ANTHROPIC_API_KEY 설정
2. 스택 기동: `infra/` 에서 `docker compose up -d`
3. chaos 주입 (시나리오별):
   - memory-pressure: `curl -X POST 'http://localhost:8080/chaos/memory-leak?mbPerMin=100'`
     (heap 추세가 잡히도록 5분 이상 대기 권장)
   - error-rate-surge: `curl -X POST 'http://localhost:8080/chaos/error-rate?percent=30'`
4. `uv run python -m scripts.run_analysis_agent [시나리오]` (agent-service 루트에서)
   시나리오: memory-pressure(기본) | error-rate-surge | latency-surge
5. 원복: `curl -X POST http://localhost:8080/chaos/reset`
"""

import sys
from datetime import UTC, datetime

from app.agents.analysis_agent import analysis_node
from app.agents.monitor_agent import monitor_node
from app.supervisor.state import IncidentInfo

# 시나리오별 인시던트 프리셋 (Alert Rule 이름은 infra/prometheus/rules 기준)
_PRESETS = {
    "memory-pressure": ("TargetAppHeapUsageHigh", "heap 사용률 85% 초과 (수동 트리거)"),
    "error-rate-surge": ("TargetAppHighErrorRate", "5xx 에러율 10% 초과 (수동 트리거)"),
    "latency-surge": ("TargetAppHighLatency", "p95 latency 3s 초과 (수동 트리거)"),
}


def main() -> None:
    scenario = sys.argv[1] if len(sys.argv) > 1 else "memory-pressure"
    alert_name, summary = _PRESETS[scenario]
    incident = IncidentInfo(
        id=f"inc-manual-{datetime.now(UTC):%Y%m%d%H%M%S}",
        scenario=scenario,
        alert_name=alert_name,
        summary=summary,
        occurred_at=datetime.now(UTC).isoformat(),
    )

    monitor_update = monitor_node({"incident": incident, "messages": []})
    monitoring = monitor_update["monitoring"]
    print("=== 모니터링 상황 요약 ===")
    print(monitoring.situation_summary)

    analysis_update = analysis_node(
        {"incident": incident, "monitoring": monitoring, "messages": []}
    )
    analysis = analysis_update["analysis"]
    print("\n=== 원인 보고서 ===")
    print(f"가설: {analysis.root_cause_hypothesis}")
    print(f"확신도: {analysis.confidence} / 심각도: {analysis.severity}")
    print("근거:")
    for evidence in analysis.evidence:
        print(f"- {evidence}")
    print("조치 후보:")
    for action in analysis.suggested_actions:
        print(f"- {action}")


if __name__ == "__main__":
    main()
