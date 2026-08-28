import httpx
from langchain.chat_models import init_chat_model
from langchain_core.language_models.chat_models import BaseChatModel

from app.config.settings import Settings
from app.tools.oauth_client import shared_auth

# openai SDK 기본값과 동일 (전체 600초·연결 5초) — 클라이언트를 직접 만들면 SDK 기본 타임아웃이 적용되지 않는다
GATEWAY_TIMEOUT = httpx.Timeout(600.0, connect=5.0)


def create_llm(settings: Settings, task_type: str | None = None) -> BaseChatModel:
    """모든 LLM 호출은 llm-gateway 경유 (ADR-0015) — 프로바이더 실키는 게이트웨이만 보유한다.

    task_type 은 게이트웨이 라우팅 정책 키 (X-Task-Type 헤더) — 모델 선택은 게이트웨이 소관이라
    에이전트 코드는 모델명을 모른다 (ADR-0007 "프로바이더를 모른다"의 게이트웨이 판).

    인증 (ADR-0016): SDK 의 api_key 는 정적이라 OAuth 토큰을 실을 수 없다 — httpx 클라이언트에 ClientCredentialsAuth
    를 달아 주입하면 SDK 가 넣은 `Bearer <api_key>` 를 요청마다 유효 토큰으로 덮어쓴다. 서비스 식별(비용·한도)은
    검증된 토큰의 client_id 가 대신하므로 X-Client-Service 자기 신고 헤더는 보내지 않는다.
    """
    headers = {}
    if task_type:
        headers["X-Task-Type"] = task_type
    auth = shared_auth(settings)
    return init_chat_model(
        settings.llm_model,
        api_key=settings.llm_api_key,
        base_url=settings.llm_base_url,
        default_headers=headers,
        # 동기(라우터 invoke)·비동기(에이전트 ainvoke) 양쪽 다 토큰 경로 — 한쪽만 주면 나머지는 SDK 기본 클라이언트(무토큰)
        http_client=httpx.Client(auth=auth, timeout=GATEWAY_TIMEOUT),
        http_async_client=httpx.AsyncClient(auth=auth, timeout=GATEWAY_TIMEOUT),
        # 게이트웨이 판정 헤더(X-Gateway-Cache/Fallback/Downgrade)를 response_metadata 로 흡수 —
        # Langfuse 재활성 시 핸들러가 그대로 수집한다. 헤더는 평범한 dict 라 체크포인트 직렬화 안전
        include_response_headers=True,
    )
