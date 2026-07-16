# agent-service

멀티 에이전트 서비스 — Python + LangGraph. 모니터링/분석/실행 에이전트가 장애 감지·원인 분석·조치 제안을 수행한다.

## 요구 사항

- [uv](https://docs.astral.sh/uv/) (Python 3.13 은 uv 가 `.python-version` 기준으로 관리)
- 설정: `.env.example` 을 `.env` 로 복사 후 값 채우기 (LLM API 키는 실 에이전트 호출 시에만 필요)

## 실행

```bash
uv sync                                        # 의존성 설치 (.venv 생성)
uv run uvicorn app.main:app --reload --port 8000
```

- 헬스체크: http://localhost:8000/health
- API 문서 (Swagger UI): http://localhost:8000/docs
- `--reload` 는 코드 변경 시 자동 재기동 (개발용)

포트 8000 사용 — 다른 스택과의 충돌 없음 (target-app 8080, Prometheus 9091, Grafana 3002, Loki 3100, Alertmanager 9093, Langfuse 3000).

## 테스트

```bash
uv run pytest
```

## 구조

```
app/
├── main.py            # FastAPI 엔트리 (/health — 인시던트 트리거는 DAY 12)
├── config/            # pydantic-settings + LLM 팩토리 (프로바이더:모델 형식, ADR-0007)
├── supervisor/        # 공유 상태 스키마 + Supervisor StateGraph
├── agents/            # 모니터링/분석/실행 에이전트 노드
└── tools/             # Prometheus(DAY 9)·Loki(DAY 10)·조치 실행(4주차) 도구
```
