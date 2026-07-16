"""Supervisor 그래프의 공유 상태 스키마.

설계 원칙: 플랫 dict 가 아닌 에이전트별 네임스페이스 — 각 에이전트는 자기 소유 필드만 쓴다.
errors 필드는 DAY 13 복원력 단계에서 추가한다 (NotRequired 구조라 하위 호환 확장 가능).
"""

from typing import Annotated, Literal, NotRequired, TypedDict

from langchain_core.messages import AnyMessage
from langgraph.graph.message import add_messages
from pydantic import BaseModel, Field

# Alert Rule 의 scenario 라벨과 1:1 매핑 (infra/prometheus/rules/target-app-alerts.yml)
Scenario = Literal["latency-surge", "error-rate-surge", "memory-pressure", "unknown"]

# 조치 카탈로그 — scenarios.md 화이트리스트 원칙: 이 목록 밖의 조치는 제안 자체가 불가
ActionType = Literal["RESTART_APP", "SCALE_OUT", "ROLLBACK", "CIRCUIT_BREAK", "NOTIFY_ONLY"]

Severity = Literal["P1", "P2", "P3"]


class IncidentInfo(BaseModel):
    """이상 이벤트 정보 — Alert payload 를 정규화한 그래프의 입력. thread_id = incident id (DAY 12)."""

    id: str
    scenario: Scenario = "unknown"
    alert_name: str | None = None
    summary: str
    occurred_at: str  # ISO 8601 문자열 — 체크포인트 직렬화 안정성 우선


class MonitoringResult(BaseModel):
    """모니터링 에이전트 소유 — 메트릭 수집 기반 상황 요약."""

    situation_summary: str
    evidences: list[str] = Field(default_factory=list)  # 수집한 메트릭 근거 (쿼리·수치)


class AnalysisResult(BaseModel):
    """분석 에이전트 소유 — 근본 원인 보고서."""

    root_cause_hypothesis: str
    evidence: list[str] = Field(default_factory=list)
    confidence: float = 0.0  # 0~1
    suggested_actions: list[str] = Field(default_factory=list)
    severity: Severity = "P3"


class ActionPlan(BaseModel):
    """실행 에이전트 소유 — 조치 계획. 실제 실행은 4주차 human-in-the-loop 승인 이후 (ADR-0005)."""

    actions: list[ActionType] = Field(default_factory=list)
    rationale: str = ""


class AIOpsState(TypedDict):
    incident: IncidentInfo
    monitoring: NotRequired[MonitoringResult | None]
    analysis: NotRequired[AnalysisResult | None]
    action: NotRequired[ActionPlan | None]
    supervisor_decision: NotRequired[str]  # 라우팅 결정 (관측·디버깅용으로 상태에 남긴다)
    messages: Annotated[list[AnyMessage], add_messages]
