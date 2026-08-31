"""실행 에이전트 — 분석 결과를 근거로 조치 계획(ActionPlan)을 생성한다.

계획 생성까지만 담당 — 실제 실행은 human-in-the-loop 승인 이후 (ADR-0005).
도구 없음: 조치 계획은 분석 보고서만으로 작성한다 (새 조회가 필요하면 그건 분석 몫).
화이트리스트는 이중 강제 — 프롬프트 지시 + ActionType Literal 스키마 검증.
"""

from functools import lru_cache

from langchain.agents import create_agent
from langchain_core.messages import AIMessage, HumanMessage

from app.config import get_settings
from app.config.llm import create_llm
from app.security.untrusted import UNTRUSTED_POLICY, wrap_untrusted
from app.supervisor.state import ActionPlan, AIOpsState

# 조치 카탈로그는 scenarios.md "화이트리스트 + 승인 필수" 원칙 기준 (ActionType 과 1:1)
ACTION_SYSTEM_PROMPT = """\
너는 AIOps 플랫폼의 실행 에이전트다. 분석 에이전트의 원인 보고서를 입력받아
조치 계획을 작성한다. 계획은 사람의 승인을 받기 전의 **제안**이다 — 여기서 실행되지 않는다.

조치 카탈로그 (이 목록 밖의 조치는 제안 자체가 불가):
- RESTART_APP: 대상 앱 컨테이너 재시작 (승인 필수 — 재시작 중 요청 유실 리스크)
- SCALE_OUT: replica 증가 (승인 필수 — 자원 비용)
- ROLLBACK: 직전 배포 버전으로 롤백 (승인 필수 — 배포가 원인일 때만 의미)
- CIRCUIT_BREAK: 문제 엔드포인트 차단 (승인 필수 — 해당 기능 사용 불가)
- NOTIFY_ONLY: 담당자 알림만 (승인 불필요 — 상태를 바꾸지 않음)

작성 지침:
- 근본 원인 가설과 severity 에 비례하는 최소 조치를 골라라 — 과잉 대응 금지.
- 원인이 코드 결함(누수 등)이면 재시작·스케일아웃은 임시 완화일 뿐임을 rationale 에 명시하라.
- expected_effect 에 조치 후 기대 상태를, risk 에 조치의 부작용을 구체적으로 적어라.
- 확신이 없거나 상태 변경이 부적절하면 NOTIFY_ONLY 만 제안하라.

환경 특성 — 조치 판단 기준 (중요): 이 플랫폼의 장애는 데모용 fault-injection 으로
재현될 수 있다. 원인 보고서가 주입을 지목하더라도 그것은 실제 장애의 대역이다 —
"인위적 주입이므로 NOTIFY_ONLY 만" 으로 결론짓지 말고, 같은 증상이 실제 운영에서
발생했다면 취할 조치를 동일하게 제안하라 (승인 여부는 사람이 판단한다).
""" + UNTRUSTED_POLICY


@lru_cache
def get_action_agent():
    """실행 에이전트를 지연 생성한다 — import 시점에 LLM API 키를 요구하지 않기 위해."""
    settings = get_settings()
    return create_agent(
        model=create_llm(settings, task_type="action-planning"),
        tools=[],
        system_prompt=ACTION_SYSTEM_PROMPT,
        response_format=ActionPlan,
    )


async def action_node(state: AIOpsState) -> dict:
    # async 인 이유: 노드 타임아웃은 협조적 취소(asyncio) 기반 — sync 노드는 지원되지 않는다 (DAY 13)
    incident = state["incident"]
    analysis = state["analysis"]
    task = HumanMessage(
        content=(
            f"인시던트 — 시나리오: {incident.scenario}, Alert: {incident.alert_name}, "
            f"심각도: {analysis.severity}, 확신도: {analysis.confidence}\n"
            f"원인 가설: {analysis.root_cause_hypothesis}\n"
            # 근거는 로그·도구 결과 인용이라 주입 문구가 그대로 옮겨질 수 있다 — 여기서도 격리
            f"근거:\n{wrap_untrusted('analysis-evidence', '; '.join(analysis.evidence) or '(없음)')}\n"
            f"분석 단계 제안: {'; '.join(analysis.suggested_actions) or '(없음)'}\n"
            "조치 계획을 작성하라."
        )
    )
    result = await get_action_agent().ainvoke({"messages": [task]})

    plan: ActionPlan = result["structured_response"]
    return {
        "action": plan,
        "messages": [
            AIMessage(content=f"[action] 계획: {plan.actions} — {plan.rationale}")
        ],
    }
