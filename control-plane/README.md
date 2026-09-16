# control-plane

관제/API/게이트웨이 — Spring Boot 4.x + Kotlin. 에이전트 오케스트레이션 진입점과 human-in-the-loop 승인 API 를 담당한다.

## 현재 제공 기능 (DAY 15~23)

- **MCP 도구 서버** (Spring AI 2.0, Streamable HTTP) — 운영 도구 3종을 표준 프로토콜로 노출
  - `getDeploymentHistory(app)` — 최근 배포 이력 (시드)
  - `searchSimilarIncidents(symptom)` — 과거 유사 인시던트 벡터 검색 (pgvector + OpenAI 임베딩)
  - `getAppConfig(app)` — 앱 런타임 설정 정보 (시드)
- **OAuth2 리소스 서버** (2026-08-26, ADR-0016) — auth-server 발급 JWT 를 issuer JWKS 로 검증 (`aud` 에
  `control-plane` 필수), 스코프 인가: `/mcp` = `ops:read` / `GET /api/incidents/**` = `ops:read` /
  `POST /api/incidents/{id}/approve|reject` = `ops:approve` / `GET /api/evaluations/**` = `ops:read` /
  `POST /api/evaluations/{id}/review` = `ops:approve` / `GET /api/experiments/**` = `ops:read` /
  `POST /webhook/alertmanager` = 공유 시크릿 bearer
  (`ALERTMANAGER_WEBHOOK_SECRET`, 필수) / actuator probe·스크레이프 permitAll. issuer 는 env `AUTH_ISSUER_URI`
  (기본 `http://localhost:8091`, docker 프로파일은 `http://auth-server:8091`). 무인증 상태는 없다 —
  MCP Inspector 등 수동 호출은 auth-server 에서 토큰을 발급받아 `--header "Authorization: Bearer <토큰>"`
- **llm-gateway OAuth 클라이언트** (2026-08-28, ADR-0016) — 임베딩 호출의 bearer 를 Client Credentials 토큰(`llm:invoke`,
  client_id `control-plane`)으로 교체. Spring AI 2.0 의 OpenAI 클라이언트는 공식 openai-java SDK(OkHttp)라 RestClient
  인터셉터가 닿지 않아 `OpenAiHttpClientBuilderCustomizer` 로 OkHttp 인터셉터(`GatewayTokenInterceptor`)를 단다 —
  발급·캐시·만료 60초 전 재발급은 `OAuth2AuthorizedClientManager`, 401 은 재발급 후 1회 재시도. 시크릿 `AUTH_CLIENT_SECRET`
  (필수), 토큰 URL `AUTH_TOKEN_URL` (기본 `http://localhost:8091/oauth2/token`). OPENAI_API_KEY 는 더 이상 쓰지 않는다
- **감사 로그** (2026-08-28, ADR-0016) — 로거명 `audit`, 필드는 MDC → docker 프로파일 ECS JSON 최상위 필드
  (`traceId` 는 tracing 이 같은 경로로 채움). 3종: `mcp_request`(`McpAuditFilter` — client_id·scope·rpc.method·tool·http.status,
  도구 본체가 MCP 서버의 별도 스레드에서 돌 수 있어 서블릿 필터에서 기록) / `approval_decision`(incident_id·status·decided_by) /
  `action_execution`(incident_id·action·ok·manual). ECS 는 점 표기 키를 중첩한다(`audit.type` → `audit:{type}`) — Loki `json` 파서가
  다시 평탄화해 `audit_type`·`http_status`·`client_id` 로 질의: `{service="control-plane"} | json | log_logger="audit" | tool="searchSimilarIncidents"`
- **MCP 도구 호출 계측** (DAY 17) — `mcp_tool_calls_seconds_*{tool, outcome}` (Spring AI 2.0.0 에
  내장 관측이 없어 명시적 계측 — `McpToolMetrics`, outcome: success/degraded/failure). Grafana
  `MCP 도구 호출` 대시보드, 전체 도구 일람은 `docs/tools-catalog.md`
- **Alertmanager webhook → Kafka 발행** (DAY 17) — `POST /webhook/alertmanager` (202 + 비동기).
  원본은 `ops.alerts.raw` 보존, firing 은 정규화(alertname→scenario, incident_id 부여) 후
  `ops.incidents` 발행. fingerprint 멱등성: 반복 발화는 병합(재발행 없음), resolved 는 활성 해제.
  Kafka 접속은 env `KAFKA_BOOTSTRAP_SERVERS` (로컬 기본 `localhost:9094`), 다운 시에도 202 유지
- **분석 결과 컨슈머 → DB 저장** (DAY 19) — `ops.analysis.results` 소비(groupId `control-plane`)
  → `incident_reports` 테이블 저장 (요약 컬럼 추출 + 보고서 원문 jsonb 보존). incident_id upsert
  멱등이라 at-least-once 재발행은 갱신만 된다. 스키마는 Flyway (`db/migration/`) 소유
- **인시던트 조회 API** (DAY 19) — `GET /api/incidents` (최신순 요약, `?limit=`),
  `GET /api/incidents/{id}` (요약 + 보고서 원문)
- **Slack 알림** (DAY 19) — 신규 보고서 저장 시 Incoming Webhook 발송 (P-등급·원인 가설·
  confidence·근거 3줄·제안 조치·상세 링크). env `SLACK_WEBHOOK_URL` 미설정이면 조용한 비활성
  (기동·발송 시점 로그로 진단 가능). 재수신(갱신)은 알림을 내지 않는다
- **보고서 품질 평가 저장·리뷰 큐** (ADR-0019) — `ops.evaluation.results` 소비 → `incident_evaluations`
  (Flyway V5·V6·V7, 자연 키 `(incident_id, prompt_version, judge_model)` upsert; 페이로드의 선택 필드
  `experiment_name`·`experiment_variant` 는 같은 이름의 컬럼에 저장 — 실험 밖 평가는 null) → 저품질(`< 0.7`)은
  `review_status=pending_review` + Slack 검토 요청(AFTER_COMMIT·@Async). `GET /api/incidents/{id}/evaluations`,
  `GET /api/evaluations/review-queue?status=&limit=`, `POST /api/evaluations/{id}/review`(`status`
  reviewed|promoted|dismissed · `human_scores` · `failure_mode` · `note` · `reviewed_by` — 라벨 규약 위반 400,
  promoted 는 종결이라 재검토 409). Alertmanager 알림 중 라벨 `kind=quality`(품질 SLO 룰)는 인시던트를
  만들지 않고 Slack 만 보낸다 (발화·해소). Slack 발신 3종은 `slack.SlackWebhookClient` 하나를 공유한다
- **A/B 실험 요약 API** (ADR-0019 결정 ③) — `GET /api/experiments/{name}/summary` — 실험 이름으로
  `incident_evaluations` 전건을 읽어 variant 별 `n`·차원 평균(`faithfulness_avg`·`actionability_avg`·
  `severity_accuracy_avg`)·`low_quality_rate` 를 이름순으로 집계 (Kotlin 집계 — 실험 1건은 수십 행).
  `experiment_variant` 가 null 인 행은 제외, 평가가 없는 실험은 빈 `variants` 로 200
  `GET /api/experiments/{name}/evaluations` 는 같은 행을 평가 응답 형식으로 그대로 준다 — 부트스트랩 CI 처럼 표본이
  필요한 계산(`evaluation-service/scripts/experiment_report.py`)의 원천. 실험 중 variant 단위 문제 감지(2026-09-16):
  Slack 검토 요청에 `실험 <name> · variant <v>` 한 줄, 품질 SLO 알림은 라벨 `aiops_experiment_name/variant`
  (`AiopsVariantFaithfulnessLow` 룰)를 읽어 머리에 `실험 <name> · <v>` 를 붙인다. 승자 판정·중단은 사람 몫
- **조치 승인 도메인** (DAY 22, ADR-0005) — `ops.actions.pending` 소비 → `action_approvals`
  저장 (활성 pending 1건 멱등) → `POST /api/incidents/{id}/approve|reject` → 전이·감사 기록 +
  `ops.actions.decisions` 발행 (접수 실패 시 롤백 = 503). 404/409 규약은 `ApprovalController`
- **Slack 승인 카드 + Socket Mode 버튼** (DAY 23, ADR-0006) — 신규 pending 저장 시 Block Kit
  카드 발송([승인][거부] 버튼), 버튼 클릭은 Socket Mode 로 수신해 승인 API 와 같은 decide 로
  수렴 (클릭한 Slack user ID = `decided_by`). 결정되면 카드 버튼 제거 + 스레드 결과 회신 —
  버튼·API·타임아웃 어느 경로든 동일. env `SLACK_BOT_TOKEN`/`SLACK_APP_TOKEN`/
  `SLACK_APPROVAL_CHANNEL` 미설정이면 해당 기능만 조용한 비활성 (승인 API 는 항상 유효)
- **승인 타임아웃** (DAY 23, ADR-0006) — 30분 미결정 시 스레드 재알림 1회 → 60분 시 expired
  전이(조치 미실행 종결, `decided_by=system`) — decisions 발행까지 승인과 같은 경로. 값은
  env `APPROVAL_REMIND_AFTER`/`APPROVAL_EXPIRE_AFTER`/`APPROVAL_SWEEP_INTERVAL` 로 설정
- **승인 조치 실행 대행** (DAY 24, ADR-0005) — approved 확정 시 AFTER_COMMIT 실행 리스너
  (전용 스레드풀)가 조치를 처리: `CIRCUIT_BREAK` = target-app `chaos/reset` 호출(자동 실행),
  `RESTART_APP` = **수동 조치 안내** (자동 실행 제외 — 2026-08-04 결정, ADR-0005 추가 사항:
  명령 예시를 카드 스레드에 회신하고 실행은 운영자 직접). 실행 감사는
  `executed_at`/`execution_note`, 결과는 decisions 페이로드(`execution[]`, `manual` 표식 포함)와
  카드 스레드 회신에 실린다. **"실행 후 발행" = 상태별 비대칭**: rejected/expired 만 decide
  트랜잭션 안 즉시 발행 (롤백 규약 유지). 미지원 조치(SCALE_OUT 등)는 명시적 실패로 기록
- **종결 보고 확장** (DAY 24) — 보고서의 approval/recovery 를 파싱해 Slack 종결 알림에
  승인·조치 실행·회복 3줄 추가 (scenarios.md "수행 내용/수행 시각/회복 여부" 스펙)

## 패키지 배치

패키지는 **도메인** 단위다. 입구(`alert`)만 단계 이름이고, 도메인 사이는 애플리케이션 이벤트로만 잇는다 (직접 주입은 입구 → 도메인 한 방향).

| 패키지 | 역할 |
|---|---|
| `alert` | Alertmanager 웹훅 입구 — 수신·파싱·분기만 (`AlertIngestService`: 인시던트 알림 → `incident`, `kind=quality` → `quality`) |
| `incident` | 인시던트 생명주기 — id 발급·중복 병합(`IncidentRegistry`)·발행 이벤트 모양(`IncidentEvent`)·보고서 저장·조회 API·보고서 Slack |
| `approval` | 조치 승인 (Slack 카드·API·타임아웃·실행) |
| `evaluation` | 보고서 품질 평가 저장·리뷰 큐·검토 요청 Slack — 케이스(보고서 1건) 단위 |
| `quality` | 품질 SLO 알림 Slack — 모집단(Prometheus 지표) 단위, `evaluation` 과 독립 |
| `messaging` | Kafka 발행 기반 (`EventPublisher`·`KafkaEventPublisher`·`OpsTopics`) — 여러 도메인이 공유 |
| `slack` | Slack Incoming Webhook 공통 클라이언트(`SlackWebhookClient` — URL·마스킹·감사·실패 정책) — `incident`·`evaluation`·`quality` 발신이 공유. 승인 카드의 Bot Token 경로(`approval/slack`)는 승인 전용이라 여기 두지 않는다 |
| `gateway` · `ops` · `security` · `audit` · `config` · `common` | LLM 게이트웨이 클라이언트 · MCP 도구 · 인가·마스킹 · 감사 로그 · 프레임워크 배선 · JPA 공통 |

## 실행

```bash
./gradlew test          # 단위 테스트 (실 DB·임베딩 API 무의존)
./gradlew bootRun       # 로컬 실행 — postgres(5433)의 controlplane DB·auth-server(8091)·ALERTMANAGER_WEBHOOK_SECRET·AUTH_CLIENT_SECRET 필요
```

컨테이너 실행은 `infra/docker-compose.yml` (전체 스택 단일 진입점). 필요한 환경 변수는 compose 의 control-plane 서비스 주석 참고.
