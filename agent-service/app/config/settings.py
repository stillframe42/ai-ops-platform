from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict

from app.experiments.definition import EXPERIMENTS_FILE


class Settings(BaseSettings):
    """기본값은 호스트 실행 기준 — compose/K8s 는 env 로 컨테이너 주소를 덮어쓴다."""

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # LLM — 모든 호출은 llm-gateway 경유 (ADR-0015). llm_client 는 와이어 프로토콜(OpenAI 호환), 모델 선택은 게이트웨이 라우팅 소관
    llm_client: str = "openai"
    llm_default_model: str = "default"  # 게이트웨이 별칭 — 실모델명 아님
    llm_base_url: str = "http://localhost:8090/v1"
    llm_api_key: str = "gateway-token"  # 자리 표시 — 실제 Authorization 은 ClientCredentialsAuth 가 OAuth 토큰으로 덮어쓴다 (ADR-0016)

    # 관측 스택 — 직접 조회 (ADR-0002)
    prometheus_url: str = "http://localhost:9091"
    loki_url: str = "http://localhost:3100"

    # MCP 도구 서버 — control-plane Streamable HTTP (ADR-0010)
    mcp_server_url: str = "http://localhost:8081/mcp"

    # OAuth2 Client Credentials (ADR-0016) — MCP·게이트웨이 공용 토큰
    auth_token_url: str = "http://localhost:8091/oauth2/token"
    auth_client_id: str = "agent-service"
    auth_client_secret: str  # 기본값 없음: 미설정 = 기동 실패 (인증 항상 필수)
    auth_scope: str = "ops:read llm:invoke"  # 생략하면 빈 스코프·aud 없는 토큰 → 리소스 서버 401. 등록 스코프의 부분집합이어야 한다

    # Kafka 인시던트 컨슈머 (ADR-0011) — 빈 문자열이면 컨슈머 비활성
    kafka_bootstrap_servers: str = "localhost:9094"

    # LangGraph 체크포인트 저장소 (ADR-0009)
    checkpoint_db_url: str | None = None

    # 시스템 프롬프트 버전 (app/prompts/{agent}/{version}.md) — 전역 기본 + 에이전트별 오버라이드
    # (예: PROMPT_VERSION_OVERRIDES='{"analysis": "v2"}' — 분석 프롬프트 실험은 이 값만 바꾼다, ADR-0019)
    prompt_version: str = "v1"
    prompt_version_overrides: dict[str, str] = {}

    # A/B 실험 정의 파일 (ADR-0019) — 기본은 패키지 동봉본. 환경별로 다른 실험을 돌리려면 경로를 덮어쓴다
    experiments_file: str = str(EXPERIMENTS_FILE)

    # OTLP 전송 — Collector 주소(스킴+호스트+포트, 경로 없음). 미설정이면 스팬·메트릭 생성만 하고 전송하지 않는다.
    # 백엔드(Tempo·Langfuse·Prometheus)는 Collector 설정 소관 (docs/otel-genai-mapping.md §2). Langfuse 도 이 경로로만
    # 받는다 — 앱은 Langfuse 키를 모른다
    otel_exporter_otlp_endpoint: str | None = None

    @property
    def llm_model(self) -> str:
        """init_chat_model 이 해석하는 "클라이언트:모델" 문자열."""
        return f"{self.llm_client}:{self.llm_default_model}"


@lru_cache
def get_settings() -> Settings:
    return Settings()
