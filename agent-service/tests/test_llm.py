"""create_llm 게이트웨이 계약 테스트 (Phase 6) — Langfuse 태깅 코드 준비의 반쪽.

게이트웨이 판정 3종(캐시 X-Gateway-Cache / 폴백 X-Gateway-Fallback / 다운그레이드
X-Gateway-Downgrade)은 전부 응답 헤더로 도착한다 — 헤더를 response_metadata 로 흡수하면
Langfuse 핸들러가 재활성 시(9월) 그대로 수집한다. 여기서는 그 배선 계약만 고정한다.
"""

from app.config.llm import create_llm
from app.config.settings import Settings


def _settings(**overrides) -> Settings:
    return Settings(_env_file=None, **overrides)


def test_llm_absorbs_gateway_response_headers():
    """응답 헤더 흡수 활성 — 게이트웨이 판정이 AIMessage.response_metadata["headers"] 에 실린다."""
    llm = create_llm(_settings(), task_type="root-cause-analysis")

    assert llm.include_response_headers is True


def test_llm_sends_gateway_routing_headers():
    """기존 계약 유지 — 라우팅 키(X-Task-Type)와 비용 식별자(X-Client-Service) 송신."""
    llm = create_llm(_settings(), task_type="root-cause-analysis")

    assert llm.default_headers["X-Task-Type"] == "root-cause-analysis"
    assert llm.default_headers["X-Client-Service"] == "agent-service"
