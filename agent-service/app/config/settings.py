from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # LLM — 모든 호출은 llm-gateway 경유 (ADR-0015).
    # llm_client 는 와이어 프로토콜(게이트웨이의 OpenAI 호환 표면 = langchain ChatOpenAI)이지 모델의
    # 프로바이더가 아니다 — 모델 선택은 게이트웨이 라우팅(X-Task-Type) 소관.
    # llm_default_model 은 게이트웨이 별칭 (실모델명 아님 — "default" = 게이트웨이 default 규칙):
    # 에이전트 설정에는 프로바이더 모델명이 등장하지 않는다
    llm_client: str = "openai"
    llm_default_model: str = "default"
    # 기본 주소는 호스트 실행 기준 (compose/K8s 는 env 로 llm-gateway 컨테이너 주소를 덮어쓴다)
    llm_base_url: str = "http://localhost:8090/v1"
    # 게이트웨이는 이 키를 검증하지 않는다 (자리 표시 — 인증 전파는 보안 주간). 프로바이더 실키는 게이트웨이만 보유
    llm_api_key: str = "gateway-local"

    # 관측 스택 — 직접 조회 (ADR-0002)
    prometheus_url: str = "http://localhost:9091"
    loki_url: str = "http://localhost:3100"

    # MCP 도구 서버 — control-plane Streamable HTTP (DAY 16, ADR-0010)
    # 기본값은 호스트 실행 기준 (control-plane 호스트 포트 8081) — compose 는 env 로 덮어쓴다
    mcp_server_url: str = "http://localhost:8081/mcp"
    mcp_api_key: str | None = None

    # Kafka 인시던트 컨슈머 (DAY 18, ADR-0011) — 기본값은 호스트 실행 기준 (compose 는 kafka:9092 로 덮어쓴다).
    # 빈 문자열이면 컨슈머 비활성 (키-게이트 관례) — 수동 트리거만으로 동작
    kafka_bootstrap_servers: str = "localhost:9094"

    # LangGraph 체크포인트 저장소 (DAY 12, ADR-0009 예정)
    checkpoint_db_url: str | None = None

    # Langfuse (DAY 14)
    langfuse_host: str | None = None
    langfuse_public_key: str | None = None
    langfuse_secret_key: str | None = None

    @property
    def llm_model(self) -> str:
        """init_chat_model 이 해석하는 "클라이언트:모델" 조합 문자열."""
        return f"{self.llm_client}:{self.llm_default_model}"


@lru_cache
def get_settings() -> Settings:
    return Settings()
