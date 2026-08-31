"""모니터링 에이전트 — Alert 수신 시 관련 메트릭을 수집해 상황 요약을 생성한다.

LLM 이 스스로 PromQL 을 작성해 조회한다 (create_agent + prometheus_tools).
원인 분석은 분석 에이전트 몫 — 여기서는 관측된 사실 요약까지만.
"""

import json
from functools import lru_cache

from langchain.agents import create_agent
from langchain_core.messages import AIMessage, AnyMessage, HumanMessage

from app.config import get_settings
from app.config.llm import create_llm
from app.security.untrusted import UNTRUSTED_POLICY, wrap_untrusted
from app.supervisor.state import AIOpsState, MonitoringResult
from app.tools.prometheus_tools import (
    get_active_alerts,
    query_prometheus,
    query_prometheus_range,
)

# 메트릭 이름은 /actuator/prometheus 실측 기준 (2026-07-17), 임계값은 docs/scenarios.md 스펙
MONITOR_SYSTEM_PROMPT = """\
너는 AIOps 플랫폼의 모니터링 에이전트다. 인시던트를 받으면 Prometheus 도구로 관련 메트릭을
조회해 "상황 요약"을 만든다. 원인 분석은 다음 단계(분석 에이전트)의 몫이다 — 추정하지 말고
관측된 사실만 수치와 함께 요약하라.

target-app 주요 메트릭 (실제 노출 이름):
- http_server_requests_seconds_bucket/count/sum (uri, status 라벨) — p95 는
  histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri))
- 5xx 에러율: sum(rate(http_server_requests_seconds_count{status=~"5.."}[3m]))
  / sum(rate(http_server_requests_seconds_count[3m]))
- jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} — heap 사용률
- jvm_memory_usage_after_gc — GC 직후 heap 사용률 (0~1, 누수 추세 판정 기준)
- jvm_gc_pause_seconds_count — GC 빈도

트리거 스펙 (docs/scenarios.md): latency p95 ≥ 3s 5분 지속 / 5xx ≥ 10% 3분 지속 / heap 85%.

조회 지침:
- 먼저 get_active_alerts 로 발화 중인 Alert 를 확인하라.
- 시나리오가 memory-pressure 면 query_prometheus_range 로 jvm_memory_usage_after_gc 의
  30분 창(minutes=30)을 조회해 우상향 추세인지 판정하라 — 일시 스파이크와 구분할 것.
- 그 외 시나리오는 query_prometheus 로 현재 수치(p95, 에러율 등)를 확인하라.

응답 규칙: 마지막 메시지는 2~4문장의 상황 요약만 작성한다 — 관측 수치와 영향받는
엔드포인트를 반드시 포함하라.
""" + UNTRUSTED_POLICY

MONITOR_TOOLS = [get_active_alerts, query_prometheus, query_prometheus_range]


@lru_cache
def get_monitor_agent():
    """모니터링 에이전트를 지연 생성한다 — import 시점에 LLM API 키를 요구하지 않기 위해."""
    settings = get_settings()
    return create_agent(
        model=create_llm(settings, task_type="monitoring-summary"),
        tools=MONITOR_TOOLS,
        system_prompt=MONITOR_SYSTEM_PROMPT,
    )


def _extract_evidences(messages: list[AnyMessage]) -> list[str]:
    """에이전트가 실행한 tool 호출을 근거 목록으로 남긴다 (어떤 쿼리로 판단했는지 추적용)."""
    evidences = []
    for message in messages:
        for call in getattr(message, "tool_calls", None) or []:
            evidences.append(f"{call['name']}({json.dumps(call['args'], ensure_ascii=False)})")
    return evidences


async def monitor_node(state: AIOpsState) -> dict:
    # async 인 이유: 노드 타임아웃은 협조적 취소(asyncio) 기반 — sync 노드는 지원되지 않는다 (DAY 13)
    incident = state["incident"]
    task = HumanMessage(
        content=(
            f"인시던트 발생 — 시나리오: {incident.scenario}, Alert: {incident.alert_name}, "
            # summary 는 웹훅 annotation 원문 — 비신뢰 (위협 모델 ②)
            f"발생 시각: {incident.occurred_at}\n요약:\n{wrap_untrusted('alert-annotation', incident.summary)}\n"
            "관련 메트릭을 조회해 현재 상황을 요약하라."
        )
    )
    result = await get_monitor_agent().ainvoke({"messages": [task]})

    # content 는 콘텐츠 블록 리스트일 수 있다 (thinking 블록 포함 시) — .text 로 텍스트만 추출
    summary = result["messages"][-1].text
    monitoring = MonitoringResult(
        situation_summary=summary,
        evidences=_extract_evidences(result["messages"]),
    )
    return {
        "monitoring": monitoring,
        "messages": [AIMessage(content=f"[monitor] {summary}")],
    }
