"""모니터링 에이전트 — Alert 수신 시 관련 메트릭을 수집해 상황 요약을 생성한다.

DAY 8 골격: 더미 결과만 반환. DAY 9 에서 create_agent + prometheus_tools 로 교체
(LLM 이 스스로 PromQL 을 작성해 조회, 시나리오 3 추세 감지는 범위 쿼리로).
"""

from langchain_core.messages import AIMessage

from app.supervisor.state import AIOpsState, MonitoringResult


def monitor_node(state: AIOpsState) -> dict:
    incident = state["incident"]
    result = MonitoringResult(
        situation_summary=f"[더미] {incident.scenario} 인시던트({incident.id}) 수신 — 메트릭 수집은 DAY 9 구현",
        evidences=[],
    )
    return {
        "monitoring": result,
        "messages": [AIMessage(content=f"[monitor] {result.situation_summary}")],
    }
