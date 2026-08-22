from langchain.chat_models import init_chat_model
from langchain_core.language_models.chat_models import BaseChatModel

from app.config.settings import Settings


def create_llm(settings: Settings, task_type: str | None = None) -> BaseChatModel:
    """모든 LLM 호출은 llm-gateway 경유 (ADR-0015) — 프로바이더 실키는 게이트웨이만 보유한다.

    task_type 은 게이트웨이 라우팅 정책 키 (X-Task-Type 헤더) — 모델 선택은 게이트웨이 소관이라
    에이전트 코드는 모델명을 모른다 (ADR-0007 "프로바이더를 모른다"의 게이트웨이 판).
    """
    # X-Client-Service: 게이트웨이의 서비스별 비용 집계·한도(rate limit·예산) 식별자 (Phase 4)
    headers = {"X-Client-Service": "agent-service"}
    if task_type:
        headers["X-Task-Type"] = task_type
    return init_chat_model(
        settings.llm_model,
        api_key=settings.llm_api_key,
        base_url=settings.llm_base_url,
        default_headers=headers,
        # 게이트웨이 판정 헤더(X-Gateway-Cache/Fallback/Downgrade)를 response_metadata 로 흡수 (Phase 6) —
        # Langfuse 재활성 시(9월) 핸들러가 그대로 수집한다. 헤더는 평범한 dict 라 체크포인트 직렬화 안전
        include_response_headers=True,
    )
