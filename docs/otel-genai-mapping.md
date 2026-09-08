# OTel GenAI 계측 매핑 — 구성요소 ↔ 시맨틱 컨벤션

> 대상: agent-service · llm-gateway · control-plane 의 LLM/에이전트/MCP 계측. 근거 규격은 OpenTelemetry GenAI 시맨틱 컨벤션(전용 리포 [`open-telemetry/semantic-conventions-genai`](https://github.com/open-telemetry/semantic-conventions-genai) — 2026-05-05 분리, 2026-09-03 기준 공식 릴리즈·태그 없음, 전 문서 상태 **Development**). 컨벤션이 이동 중이므로 아래 pin 표의 버전을 기준으로 속성을 고정하고, 버전을 올릴 때 이 문서의 속성 목록을 diff 한다. 결정 배경은 [ADR-0018](adr/0018-observability-vendor-neutral.md).

## 1. 버전 고정 (pin)

| 구성 | 버전 | 비고 |
|---|---|---|
| opentelemetry-sdk / api / exporter-otlp-proto-http (Python) | 1.44.0 | exporter 는 명시 의존 |
| opentelemetry-semantic-conventions / instrumentation-httpx | 0.65b0 | `gen_ai_attributes` 60종 + `mcp_attributes` 4종 수록 |
| opentelemetry-instrumentation-openai-v2 | 2.4b0 | Client Spans·Metrics 발신 (openai SDK 훅 — 게이트웨이 호출 경로) |
| opentelemetry-util-genai | 1.1b0 | Agent·Tool·Workflow 스팬 핸들러 — 실험 컨벤션 전용 |
| Kafka 전파 (agent-service) | 수동 스팬 `app/events/propagation.py` | aiokafka 계측(0.65b0)은 미사용 — 배치 `getmany` 소비라 메시지 단위 계측기가 맞지 않음. 소비 `{topic} process` CONSUMER · 발행 `{topic} send` PRODUCER + `traceparent` 헤더 |
| 옵트인 환경변수 | `OTEL_SEMCONV_STABILITY_OPT_IN=gen_ai_latest_experimental` | openai-v2 문서 명시 |
| Spring AI (llm-gateway·control-plane) | 2.0.0 | ChatModel 관측이 `gen_ai.system` 세대 속성 발신 — §5 정규화 |
| micrometer-tracing-bridge-otel / opentelemetry (JVM) | 1.7.0 / 1.62.0 | Spring Boot 4.1.0 관리, `opentelemetry-exporter-otlp` 추가. Spring Security 관측은 인증만 (`SecurityObservationSettings`, §5) |
| otel-collector-contrib / Tempo | 0.159.0 / 2.9.0 | compose 이미지 태그 = 차트 의존(`opentelemetry-collector` 0.172.0·`tempo` 1.24.4)의 이미지. Tempo 는 local-blocks 프로세서로 TraceQL 메트릭 |
| Langfuse server / worker | 3.222.0 | OTLP traces 수신 `/api/public/otel/v1/traces` (Basic public:secret), logs 엔드포인트 없음 |

## 2. 파이프라인

앱은 표준 어휘로 계측하고 Collector 하나만 바라본다. 백엔드 교체·추가는 Collector exporter 설정으로 끝나며 앱 재배포가 없다.

```mermaid
flowchart LR
    subgraph apps["애플리케이션 — 계측은 표준 어휘만"]
        AS["agent-service<br/>OTel SDK · gen_ai.* / mcp.*"]
        GW["llm-gateway<br/>Micrometer→OTel · Spring AI gen_ai.*"]
        CP["control-plane<br/>Micrometer→OTel · MCP 서버 스팬"]
    end
    COL["OTel Collector (contrib)<br/>receivers: otlp<br/>processors: memory_limiter · batch · transform · filter<br/>exporters: otlp · otlphttp · prometheus"]
    TEMPO[("Tempo — 트레이스")]
    PROM[("Prometheus — gen_ai 메트릭 스크레이프")]
    LF[("Langfuse — LLM 세션·비용<br/>compose 전용")]
    GRAF["Grafana — Tempo·Prometheus·Loki 상관 (traceId)"]
    AS & GW & CP -->|OTLP| COL
    COL --> TEMPO
    COL -->|prometheus exporter| PROM
    COL -.->|agent-service 스팬만| LF
    TEMPO & PROM --> GRAF
```

| 항목 | compose | K8s (Helm umbrella) |
|---|---|---|
| Collector | `otel-collector` 서비스 (`infra/otel/collector.yml`) | `opentelemetry-collector` 차트 의존, Deployment |
| Tempo | `tempo` 서비스 (monolithic, 로컬 스토리지) | `tempo` 차트 의존 |
| Langfuse exporter | 활성 (compose 스택 내 Langfuse) | 없음 — Langfuse 미배포 |
| 메트릭 | Collector `prometheus` exporter → Prometheus 스크레이프 잡 | 동일 + ServiceMonitor |
| 콘텐츠 | 스팬 속성 캡처 후 Tempo 경로에서 삭제 (§6) | 캡처 안 함 |

두 형상의 차이는 Langfuse exporter 유무 하나다. 이 차이가 "백엔드 교체 = Collector 설정 변경"의 실증이다.

## 3. 구성요소 ↔ 컨벤션 계층

| 계층 | 우리 구성요소 | 스팬/메트릭 이름 | 필수·권장 속성 (규격) | 발신 지점 |
|---|---|---|---|---|
| Client Spans (`gen-ai-spans.md`) | agent-service → llm-gateway 채팅 호출 | `chat {gen_ai.request.model}` = `chat default`, CLIENT | 필수 `gen_ai.operation.name`·`gen_ai.provider.name` / 조건부 `gen_ai.request.model`·`gen_ai.conversation.id`·`server.port`·`error.type` / 권장 `gen_ai.response.model`·`gen_ai.usage.input_tokens`·`gen_ai.usage.output_tokens`·`gen_ai.response.finish_reasons`·`gen_ai.response.id`·`server.address` | openai-v2 계측 (자동) |
| Client Spans — 서버 측 | llm-gateway → Anthropic/OpenAI | `chat claude-sonnet-5` 등, **INTERNAL** (2026-09-08 실측 — Spring AI 는 CLIENT kind 를 쓰지 않는다, 상류 HTTP 는 자식 `POST` CLIENT 스팬) | Spring AI 발신: `gen_ai.system`(→ Collector 가 `gen_ai.provider.name` 복제)·`gen_ai.operation.name`·`gen_ai.request.{model,max_tokens,…}`·`gen_ai.response.{id,model,finish_reasons}`(finish_reasons 는 JSON 문자열 → Collector 가 배열화)·`gen_ai.usage.{input,output,total}_tokens`·`gen_ai.usage.cache_{creation,read}.input_tokens`·`spring.ai.model.request.tool.names` | Spring AI ChatModel 관측 (자동). 부모 = MVC SERVER `http post /v1/chat/completions` — 감사 필터가 `aiops.client_id`·`aiops.scope`·`gateway.*` 를 부여 (§5) |
| Agent Spans (`gen-ai-agent-spans.md`) | LangGraph 실행 1건 | `invoke_workflow incident-response`, INTERNAL | 필수 `gen_ai.operation.name=invoke_workflow` / 조건부 `gen_ai.workflow.name`·`gen_ai.conversation.id` | raw span (`agent_spans.workflow_span`) — util-genai `workflow()` 는 span link·시작 시점 속성을 받지 못해 같은 속성명으로 직접 연다. 재개 실행은 `aiops.resumed=true` + 원 실행으로 link |
| Agent Spans | Supervisor 라우팅·monitor·analysis·action 노드 | `invoke_agent {gen_ai.agent.name}`, INTERNAL | 필수 `gen_ai.operation.name=invoke_agent` / 조건부 `gen_ai.agent.name`·`gen_ai.conversation.id` / 권장 `gen_ai.request.model`·`gen_ai.usage.*` | util-genai `invoke_local_agent()` (노드 래핑) |
| Agent Spans — 도구 | 로컬 도구 5종(`query_prometheus`·`query_prometheus_range`·`compare_with_baseline`·`get_active_alerts`·`get_app_logs`) + MCP 도구 3종 | `execute_tool {gen_ai.tool.name}`, INTERNAL | 필수 `gen_ai.operation.name=execute_tool` / 조건부 `gen_ai.tool.name`·`gen_ai.tool.call.id`·`error.type` / 권장 `gen_ai.tool.type`·`gen_ai.tool.description` / 옵트인 `gen_ai.tool.call.arguments`·`gen_ai.tool.call.result` | util-genai `tool()` (도구 래퍼) |
| MCP (`mcp.md`) — 클라이언트 | agent-service → control-plane `tools/call` | 별도 스팬 없음 — 위 `execute_tool` 스팬에 MCP 속성 추가 (규격: 외부 GenAI 계측이 도구 실행을 추적 중이면 별도 스팬을 만들지 않는다) | `mcp.method.name=tools/call`·`gen_ai.tool.name` / 권장 `mcp.session.id`·`mcp.protocol.version`·`server.address`·`server.port`·`network.protocol.name=http` | MCP 도구 래퍼 (`mcp_tools.py`) |
| MCP — 서버 | control-plane MCP 서버 | 두 스팬으로 나뉜다 — ① HTTP 서버 스팬 `http post /mcp` SERVER (Spring MVC 자동) ② 도구 본체 `tools/call {gen_ai.tool.name}` **INTERNAL** (①의 자식 — 같은 요청에 SERVER 를 둘 두지 않는다) | ① 에 `mcp.method.name`(initialize·tools/list·tools/call…)·`gen_ai.tool.name`·`jsonrpc.request.id`·`client.address` + `aiops.client_id`·`aiops.scope` — 요청 계층 값은 감사 필터(`McpAuditFilter`)가 부여 / ② 에 `mcp.method.name=tools/call`·`gen_ai.operation.name=execute_tool`·`gen_ai.tool.name`·`aiops.outcome`(success/degraded/failure)·`error.type` — 규격의 `mcp.session.id`·`mcp.protocol.version` 은 서버 측 접근 경로가 없어 클라이언트 스팬만 보유 | ① Spring MVC 관측 + `McpAuditFilter` / ② `McpToolMetrics` 래핑 지점 (Spring AI 2.0.0 MCP 모듈은 관측 미제공, OTel API 직접 — Observation 을 쓰면 `mcp.tool.calls` 타이머와 이중) |
| Events (`gen-ai-events.md`) | 프롬프트·응답 본문 | `gen_ai.client.inference.operation.details` (로그 시그널) 또는 스팬 속성 `gen_ai.input.messages`·`gen_ai.output.messages`·`gen_ai.system_instructions` — 전부 **옵트인** | 구조화 형식 `{role, parts:[{type, content}]}` | openai-v2 계측, 캡처 모드 환경변수 (§6) |
| Metrics (`gen-ai-metrics.md`) | LLM 호출 토큰·지연 | `gen_ai.client.token.usage` ({token}, histogram, `gen_ai.token.type=input\|output`) · `gen_ai.client.operation.duration` (s) | 필수 `gen_ai.operation.name`·`gen_ai.provider.name`(·`gen_ai.token.type`) / 조건부 `gen_ai.request.model`·`server.port` / 권장 `server.address` | openai-v2 계측 → OTel Metrics SDK → OTLP |
| Metrics — MCP | MCP 호출 지연 | 규격 `mcp.client/server.operation.duration` 은 발신하지 않는다 — 클라이언트 = Tempo **TraceQL 메트릭**(`execute_tool` 스팬에서 `quantile_over_time(duration, .95) by (span.gen_ai.tool.name)`, local-blocks), 서버 = 기존 `mcp.tool.calls` 타이머(우리 확장) | `gen_ai.tool.name`·`mcp.method.name` | Tempo metrics-generator(두 형상) · control-plane Micrometer |
| Provider (`openai.md`·`anthropic.md`) | 프로바이더별 확장 | `gen_ai.openai.*`·`gen_ai.usage.cache_*` 등 | Spring AI 가 발신하는 범위만 | 자동 |

`gen_ai.conversation.id` 는 **인시던트 id** 다. 규격은 "라이브러리나 앱이 대화 식별자를 명시적으로 제공할 때만" 채우고 UUID·traceId 를 대체값으로 쓰지 말라고 하므로, LangGraph `thread_id`(= incident id)를 앱이 명시적으로 부여한다. Langfuse 는 이 속성을 세션으로 인식한다(2026-09-03 실측 — `session.id`·`langfuse.session.id` 도 인식하나 표준 이름만 쓴다).

## 4. 인시던트 1건의 스팬 트리 (실측 — compose, 2026-09-07 E2E 3차 + 2026-09-08 정합)

```
http post /webhook/alertmanager                    control-plane · SERVER (Alertmanager 웹훅) — trace 루트
└─ ops.alerts.raw send · ops.incidents send         control-plane · PRODUCER (Spring Kafka observation, traceparent 헤더) — @Async 경계는 전용 풀 데코레이터로 전파
   └─ ops.incidents process                         agent-service · CONSUMER (헤더 추출 — propagation.py)
      └─ invoke_workflow incident-response          agent-service · INTERNAL · gen_ai.conversation.id=inc-… · incident.id · aiops.resumed=false
         ├─ invoke_agent supervisor                 gen_ai.agent.name=supervisor · aiops.node (라우팅 결정)
         │  └─ chat default                         CLIENT (openai-v2) · gen_ai.response.model=claude-haiku-4-5-… · gateway.task_type/cache/guardrail
         │     └─ POST                              httpx CLIENT
         │        └─ http post /v1/chat/completions llm-gateway · SERVER · aiops.client_id/scope · gateway.* (감사 필터)
         │           ├─ authenticate bearertoken    llm-gateway · INTERNAL (Security — 인증만 관측)
         │           └─ chat claude-haiku-4-5       llm-gateway · INTERNAL (Spring AI) · gen_ai.system→gen_ai.provider.name=anthropic
         │              └─ POST                     llm-gateway · CLIENT → Anthropic
         ├─ invoke_agent monitor
         │  ├─ chat default → … (위와 동일 구조)
         │  ├─ execute_tool get_active_alerts       로컬 도구 → GET (httpx CLIENT → Prometheus/Alertmanager)
         │  └─ execute_tool getDeploymentHistory    MCP 속성: mcp.method.name=tools/call · mcp.session.id · mcp.protocol.version · server.address/port
         │     └─ POST                              httpx CLIENT (도구 호출마다 initialize → tools/call 세션)
         │        └─ http post /mcp                 control-plane · SERVER · mcp.method.name · gen_ai.tool.name · jsonrpc.request.id · client.address · aiops.client_id/scope
         │           ├─ authenticate bearertoken    control-plane · INTERNAL
         │           └─ tools/call getDeploymentHistory   control-plane · INTERNAL (McpToolMetrics) · gen_ai.operation.name=execute_tool · aiops.outcome
         ├─ invoke_agent analysis · invoke_agent action   (동일 구조 — 로컬 도구·MCP 도구·chat)
         ├─ approval                                interrupt — 승인 대기, 여기서 trace 종료 · aiops.node=approval
         └─ ops.actions.pending send · ops.analysis.results send   agent-service · PRODUCER → control-plane `{topic} process` CONSUMER (같은 trace)

http post /api/incidents/{incidentId}/approve      control-plane · SERVER (승인 API) — 새 trace 루트
└─ ops.actions.decisions send                       control-plane · PRODUCER (조치 실행 풀 → 데코레이터 전파)
   └─ ops.actions.decisions process                 agent-service · CONSUMER
      └─ invoke_workflow incident-response          aiops.resumed=true · **link → 위 trace 의 invoke_workflow** (aiops.link.reason=resume-after-approval)
         ├─ approval → recovery                     규칙 폴링 (LLM 없음) · aiops.node
         └─ invoke_agent supervisor
```

`approval`·`recovery` 는 LLM 이 없는 노드라 GenAI 계층 밖이다 — 우리 확장 속성 `aiops.node` 로 구분한다. 실측 규모: 인시던트 1건(승인 없는 합성 발화)이 control-plane 34 · agent-service 54 · llm-gateway 80 스팬 (2026-09-08 — Security 관측 축소 전에는 control-plane 140). 승인 전후 두 trace 는 Tempo 에서 link 로 이동 가능하고, Loki 는 traceId 로 감사 로그(`audit.type=mcp_request`·`gateway_request`)를 대조한다.

## 5. 우리 확장 속성과 정규화 규칙

표준이 다루지 않는 것은 자체 네임스페이스에 둔다. 표준 네임스페이스(`gen_ai.*`·`mcp.*`)에 임의 키를 추가하지 않는다.

| 네임스페이스 | 속성/메트릭 | 출처 |
|---|---|---|
| `incident.id` | 인시던트 id (스팬 속성 — `gen_ai.conversation.id` 와 같은 값, Tempo 검색 축) | agent-service |
| `aiops.node` | LangGraph 노드 이름 (supervisor·monitor·analysis·action·approval·recovery) | agent-service |
| `gateway.task_type`·`gateway.cache`·`gateway.guardrail`·`gateway.guardrail_stage`·`gateway.downgrade`·`gateway.fallback` | 게이트웨이 판정 (요청 헤더 `X-Task-Type`, 응답 헤더 `X-Gateway-*`) — 클라이언트 스팬(`chat default`)과 서버 스팬(`http post /v1/chat/completions`)에 **같은 키**. 헤더가 없으면 속성도 없다 | agent-service (httpx 응답 훅) · llm-gateway (감사 필터) |
| `gateway.*` 메트릭 11종 · `mcp.tool.calls` | 기존 Micrometer 유지 (캐시·비용·예산·가드레일·마스킹 — 표준에 대응물 없음) | llm-gateway · control-plane |
| `aiops.client_id`·`aiops.scope` | 감사 로그 MDC 필드(`client_id`·`scope`)를 HTTP 서버 스팬 속성으로도 부여 — Loki 축과 Tempo 축에서 같은 질의 (감사 필터 `GatewayAuditFilter`·`McpAuditFilter`) | llm-gateway · control-plane |
| `aiops.node`·`aiops.resumed`·`aiops.link.reason`·`aiops.outcome` | 노드 이름 / 재개 실행 여부 / span link 사유(`resume-after-approval`) / MCP 도구 결과 3분류(success·degraded·failure) | agent-service · control-plane |

Collector `transform` 규칙:

| 규칙 | 대상 | 이유 |
|---|---|---|
| `gen_ai.system` → `gen_ai.provider.name` 복제 | llm-gateway 스팬 (Spring AI 2.0.0) | 세대 차이 정규화 — 원본 키는 유지 (2026-09-08 실측: 복제 확인) |
| `gen_ai.response.finish_reasons` 문자열 → 배열 (`ParseJSON`) | llm-gateway 스팬 (Spring AI 는 `'["tool_use"]'` 문자열로 발신) | Python 계측(배열)과 타입 정합 — 값 어휘는 정규화하지 않는다 (아래) |
| `gen_ai.input.messages`·`gen_ai.output.messages`·`gen_ai.system_instructions` 삭제 | Tempo exporter 경로 | 콘텐츠는 Langfuse 경로만 (§6) |
| `service.name == agent-service` 만 통과 (`filter`) | Langfuse exporter 경로 | 같은 LLM 호출이 agent-service(클라이언트)·llm-gateway(Spring AI) 두 스팬으로 나오므로 비용 이중 집계 방지 |

**`finish_reasons` 값 어휘는 두 축이다** — agent-service(OpenAI 호환 응답) `tool_calls`/`stop`, llm-gateway(Anthropic 원어) `tool_use`/`end_turn`. 규격은 프로바이더 원어를 허용하므로 보정하지 않고, Tempo 질의는 계층별로 한다 (클라이언트 스팬 = `stop|tool_calls`, 게이트웨이 스팬 = `end_turn|tool_use`).

**Prometheus 의 `gen_ai_*` 시리즈도 두 축이다** — `job=otel-collector` 는 Python 계측의 표준 이름(`gen_ai_client_token_usage_*` histogram, `gen_ai_client_operation_duration_seconds_*`, 라벨 `gen_ai_provider_name`·`gen_ai_response_model`), `job=llm-gateway`·`control-plane` 은 Spring AI Micrometer 이름(`gen_ai_client_operation_seconds_*`, `gen_ai_client_token_usage_total` counter, 라벨 `gen_ai_system`). 대시보드 `genai-observability` 는 패널마다 축을 명시한다. 이름을 합치는 Collector 규칙은 두지 않는다 — Spring AI 세대가 바뀌면 이름도 따라오므로 대시보드 쿼리만 옮긴다.

**Spring Security 관측은 인증만 남긴다** — 기본은 요청 체인(`security filterchain before/after`·`secured request`)·인가·인증 전부 스팬이라 요청당 4~5 스팬이 붙는다 (2026-09-08 실측: control-plane 스팬 140 중 117). `SecurityObservationSettings` 빈으로 요청 체인·인가 관측을 끄고 `authenticate bearertoken`(JWT 검증·JWKS 조회 — 실지연이 있는 유일한 구간)만 둔다. Collector filter 가 아니라 원천에서 끄는 이유는 생성·직렬화·전송 비용 자체를 없애기 위해서다. Collector filter 는 우리가 끌 수 없는 계측에만 쓴다.

`gen_ai.provider.name` 은 계측기가 "아는 범위"로 채운다는 규격(프록시·호스팅 플랫폼 경유 시 실제 상류와 다를 수 있음)에 따라, agent-service 클라이언트 스팬은 `openai`(OpenAI 호환 엔드포인트) 로 두고 보정하지 않는다. 실프로바이더는 llm-gateway 스팬(`anthropic`/`openai`)과 `gateway.requests{provider}` 메트릭이 담당한다. 모델 식별은 두 스팬 모두 `gen_ai.response.model`(게이트웨이가 폴백·다운그레이드를 반영한 실모델을 응답 `model` 필드로 반환)로 한다.

## 6. 콘텐츠 캡처 정책

프롬프트 본문은 규격상 옵트인이며 PII 를 포함할 수 있다. 우리 시스템에서는 캡처 지점(agent-service)이 llm-gateway 입력 마스킹보다 **앞**이라, 캡처된 본문은 마스킹 전 원문이다.

| 프로파일 | `OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT` | 결과 |
|---|---|---|
| 로컬 (compose) | `SPAN_ONLY` | 스팬 속성 `gen_ai.input/output.messages` 로 캡처 → Langfuse 입출력 표시(2026-09-03 실측), Tempo 경로는 Collector 가 삭제 |
| 운영 (K8s) | 미설정 (`NO_CONTENT`) | 캡처 없음 — 원문이 관측 백엔드로 나가지 않는다 |

`EVENT_ONLY`(로그 이벤트 `gen_ai.client.inference.operation.details`)가 규격이 권장하는 형태이나, Tempo 와 Langfuse 모두 OTLP 로그를 소비하지 않아(Langfuse logs 엔드포인트 없음 — 실측 404) 채택하지 않는다. Loki 로 보내는 대안은 필요가 생길 때 판단한다. Spring AI 의 `spring.ai.chat.observations.log-prompt/log-completion` 은 로그 출력 방식이며 기본 false 를 유지한다.

## 7. 갱신 절차

1. pin 표의 버전을 바꾸기 전에 `agent-service/tests/test_otel.py` 의 속성 계약 테스트가 GREEN 인지 확인한다.
2. 버전을 올린 뒤 스크래치 실측(목 게이트웨이 + InMemory exporter)으로 발신 속성 목록을 뽑아 §3 표와 diff 한다.
3. 이름이 바뀐 속성은 Collector `transform` 에 구키→신키 복제 규칙을 두고, 대시보드 쿼리를 신키로 옮긴 뒤 규칙을 제거한다 (이중 발신 기간 운영).
4. 변경 내용을 이 문서와 ADR-0018 추가 사항에 날짜와 함께 기록한다.
