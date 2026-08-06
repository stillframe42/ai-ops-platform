from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # LLM — "프로바이더:모델" 형식 (ADR-0007 추가 사항: 설정으로 프로바이더 전환)
    llm_model: str = "anthropic:claude-sonnet-5"
    anthropic_api_key: str | None = None
    openai_api_key: str | None = None

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

    # A2A 서버 병행 노출 (Phase 5 실험 — feature/a2a-experiment 전용, 키-게이트 관례)
    # base_url 은 Agent Card 에 실리는 외부 접근 주소 — 마운트된 앱의 공개 주소와 일치해야 한다
    a2a_enabled: bool = False
    a2a_base_url: str = "http://localhost:8000/"

    # Langfuse (DAY 14)
    langfuse_host: str | None = None
    langfuse_public_key: str | None = None
    langfuse_secret_key: str | None = None

    @property
    def llm_provider(self) -> str:
        return self.llm_model.split(":", 1)[0]

    def active_llm_api_key(self) -> str:
        """활성 프로바이더의 API 키만 검증한다 — 비활성 프로바이더 키는 없어도 된다."""
        key = {
            "anthropic": self.anthropic_api_key,
            "openai": self.openai_api_key,
        }.get(self.llm_provider)
        if not key:
            raise ValueError(
                f"LLM_MODEL={self.llm_model} 에 필요한 {self.llm_provider.upper()}_API_KEY 가 없습니다 (.env 확인)"
            )
        return key


@lru_cache
def get_settings() -> Settings:
    return Settings()
