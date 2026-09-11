from functools import lru_cache
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict

SampleProfile = Literal["experiment", "production"]


class Settings(BaseSettings):
    """기본값은 호스트 실행 기준 — compose/K8s 는 env 로 컨테이너 주소를 덮어쓴다 (agent-service 와 같은 규약)."""

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # Judge LLM — 모든 호출은 llm-gateway 경유 (ADR-0015). 모델은 게이트웨이 `evaluation-judge` 규칙이 고른다
    llm_base_url: str = "http://localhost:8090/v1"
    llm_api_key: str = "gateway-token"  # 자리 표시 — 실제 Authorization 은 ClientCredentialsAuth 가 OAuth 토큰으로 덮어쓴다

    # 관측 스택 — 시간창 재조회 (docs/quality-evaluation.md §4), agent-service 와 같은 직접 조회 (ADR-0002)
    prometheus_url: str = "http://localhost:9091"
    loki_url: str = "http://localhost:3100"

    # OAuth2 Client Credentials (ADR-0016) — 게이트웨이 호출용. 이 서비스는 MCP 를 쓰지 않으므로 llm:invoke 만
    auth_token_url: str = "http://localhost:8091/oauth2/token"
    auth_client_id: str = "evaluation-service"
    auth_client_secret: str  # 기본값 없음: 미설정 = 기동 실패 (인증 항상 필수)
    auth_scope: str = "llm:invoke"

    # Kafka — ops.analysis.results 소비 (별도 컨슈머 그룹). 빈 문자열이면 컨슈머 비활성
    kafka_bootstrap_servers: str = "localhost:9094"

    # 샘플링 프로파일 (§3) — experiment 100% / production 층화 비율
    eval_sample_profile: SampleProfile = "experiment"
    # Judge 프롬프트 버전 (§7) — `evaluation/prompts/judge/<version>.md`, 페이로드·메트릭·baseline 에 그대로 실린다
    eval_judge_prompt_version: str = "v2"
    # 시간창 재조회 on/off — Prometheus·Loki 없는 환경(단위 테스트·스택 없는 로컬)에서 끈다
    eval_evidence_enabled: bool = True

    # OTLP 전송 — Collector 주소. 미설정이면 스팬 생성만 하고 전송하지 않는다 (agent-service 와 동일 키-게이트)
    otel_exporter_otlp_endpoint: str | None = None


@lru_cache
def get_settings() -> Settings:
    return Settings()
