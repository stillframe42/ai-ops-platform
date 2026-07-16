"""분석 에이전트 — 모니터링 요약을 입력으로 근본 원인 가설을 세우고 도구로 검증한다.

DAY 8 골격: 더미 결과만 반환. DAY 10 에서 create_agent + loki_tools 등 ReAct 루프로 교체.
더미 severity 는 P2 로 두어 action 경로까지 end-to-end 로 흐르게 한다 (P3 조기 종료는 테스트에서 검증).
"""

from langchain_core.messages import AIMessage

from app.supervisor.state import AIOpsState, AnalysisResult


def analysis_node(state: AIOpsState) -> dict:
    result = AnalysisResult(
        root_cause_hypothesis="[더미] 근본 원인 분석은 DAY 10 구현",
        confidence=0.0,
        severity="P2",
    )
    return {
        "analysis": result,
        "messages": [AIMessage(content=f"[analysis] {result.root_cause_hypothesis}")],
    }
