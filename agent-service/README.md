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
├── events/            # Kafka 인시던트 컨슈머 (DAY 18, ADR-0011)
└── tools/             # Prometheus(DAY 9)·Loki(DAY 10)·MCP 클라이언트(DAY 16)·조치 실행(DAY 21~) 도구
```

## Kafka 인시던트 컨슈머 (DAY 18, ADR-0011)

`ops.incidents` 를 소비해 그래프를 자동 트리거하고, 종료 시 `ops.analysis.results` 로
결과를 발행한다 (실패 종료도 errors 포함 부분 보고서 — DAY 13 정합). 수동 커밋
at-least-once: 재전달 중복은 done 검사(재실행 생략 + 결과 재발행)와 미완 체크포인트
resume(Durable Execution)이 흡수한다. 수동 트리거 `POST /incidents/trigger` 는 디버그용으로 그대로 둔다.

- `KAFKA_BOOTSTRAP_SERVERS` — 기본 `localhost:9094` (호스트 실행), compose 는 `kafka:9092` 로
  덮어씀. **빈 값이면 컨슈머 비활성** (수동 트리거만으로 동작). 브로커 다운 시 앱은 뜨고
  컨슈머만 백오프 재시도

## 관측 — OTel GenAI 표준 어휘 (ADR-0018, `docs/otel-genai-mapping.md`)

LLM 호출·에이전트 노드·도구 호출을 OTel GenAI 시맨틱 컨벤션으로 계측한다 (`app/config/otel.py`·`otel_genai.py`·`agent_spans.py`) —
`chat {model}` Client Span(contrib openai-v2, 토큰·모델·finish_reasons + 게이트웨이 판정 헤더 `gateway.*` 승격) /
`invoke_workflow incident-response`·`invoke_agent {노드}`·`execute_tool {도구}` Agent Spans(util-genai, MCP 도구는 `mcp.method.name` 등 동반) /
`gen_ai.client.token.usage`·`operation.duration` 메트릭. 세션 축은 `gen_ai.conversation.id` = incident id (하위 스팬 상속), 승인 대기 전후 실행은 span link.
Kafka 소비·발행은 `traceparent` 헤더로 control-plane 과 한 trace 다. 벤더 SDK(Langfuse 콜백) 의존은 없다 — Langfuse 는 Collector 가 라우팅하는 백엔드 중 하나.

- `OTEL_EXPORTER_OTLP_ENDPOINT` — Collector 주소 (compose 는 `http://otel-collector:4318`, 호스트 실행은 `http://localhost:4318`). **미설정 = 전파만 하고 전송 없음**, 메트릭도 함께 꺼진다
- `OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT` — 프롬프트/응답 본문 캡처. 미설정 = `NO_CONTENT`(운영). compose 는 `SPAN_ONLY`(Langfuse 표시용) — 캡처 본문은 게이트웨이 마스킹 **전** 원문이라 Tempo 경로는 Collector 가 삭제한다
- `PROMPT_VERSION`(기본 `v1`)·`PROMPT_VERSION_OVERRIDES`(JSON, 예 `{"analysis": "v2"}`) — 시스템 프롬프트 버전. 본문은 `app/prompts/{monitor,analysis,action,router}/<version>.md`, 버전은 `invoke_agent` 스팬 `aiops.prompt.version` 과 보고서 `analysis.prompt_version` 에 실린다 (ADR-0019 실험 축)
- `/health` 의 `otlp_enabled` 로 전송 여부 확인. 속성 계약 테스트는 `tests/test_otel.py`

## MCP 도구 (DAY 16, ADR-0010)

운영 도구(배포 이력·유사 인시던트 검색·앱 설정)는 control-plane MCP 서버에서 프로토콜로
발견한다 (`app/tools/mcp_tools.py`) — 도구 이름·스키마가 이 저장소에 없고, 서버에 도구가
추가되면 다음 발견 시점에 자동 반영된다. 관련 환경 변수 (`.env`, git 미추적):

- `MCP_SERVER_URL` — 기본 `http://localhost:8081/mcp` (호스트 실행), compose 는 내부 주소로 덮어씀
- `AUTH_TOKEN_URL` — auth-server 토큰 엔드포인트, 기본 `http://localhost:8091/oauth2/token` (compose 는 내부 주소로 덮어씀)
- `AUTH_CLIENT_ID` — 기본 `agent-service` (auth-server 등록명)
- `AUTH_CLIENT_SECRET` — **필수** (기본값 없음, 미설정 = 기동 실패). infra/.env 의 `AUTH_CLIENT_SECRET_AGENT_SERVICE` 와 같은 값

`/mcp` 호출과 llm-gateway 호출은 같은 OAuth2 Client Credentials bearer 토큰(스코프 `ops:read llm:invoke`, aud 2개, ADR-0016) —
`app/tools/oauth_client.py` 의 `httpx.Auth` 가 발급·캐시·만료 60초 전 재발급·401 시 1회 재시도를 요청 시점에 처리한다
(프로세스 공유 1개 — `shared_auth`). 게이트웨이 쪽은 openai SDK 의 `api_key` 가 정적이라 `ChatOpenAI` 에 이 Auth 를 단
httpx 클라이언트(`http_client`·`http_async_client`)를 주입해 SDK 가 넣은 bearer 를 덮어쓴다 — 서비스 식별(비용·한도)은
토큰 `client_id` 가 대신하므로 `X-Client-Service` 헤더는 보내지 않는다. 승인 권한(`ops:approve`)은 이 클라이언트에
등록돼 있지 않아 에이전트 토큰으로는 승인 API 가 403 이다.

MCP 서버 다운 시: 도구 발견 실패는 로컬 도구만으로 강등해 부분 진행하고 다음 실행에서
재발견, 호출 실패는 DAY 13 복원력 경로(NodeFailure 기록 → 부분 보고서)로 이어진다.
