# control-plane

관제/API/게이트웨이 — Spring Boot 4.x + Kotlin. 에이전트 오케스트레이션 진입점과 human-in-the-loop 승인 API 를 담당한다.

## 현재 제공 기능 (DAY 15~19)

- **MCP 도구 서버** (Spring AI 2.0, Streamable HTTP) — 운영 도구 3종을 표준 프로토콜로 노출
  - `getDeploymentHistory(app)` — 최근 배포 이력 (시드)
  - `searchSimilarIncidents(symptom)` — 과거 유사 인시던트 벡터 검색 (pgvector + OpenAI 임베딩)
  - `getAppConfig(app)` — 앱 런타임 설정 정보 (시드)
- **/mcp API Key 인증** (DAY 16) — `X-API-Key` 헤더, 키는 env `MCP_API_KEY`
  (미설정 시 인증 생략 — 로컬 개발 편의. OAuth 2.1 전환은 8월 보안 주간).
  MCP Inspector 로 호출할 때는 `--header "X-API-Key: <키>"` 필요
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

## 실행

```bash
./gradlew test          # 단위 테스트 (실 DB·임베딩 API 무의존)
./gradlew bootRun       # 로컬 실행 — postgres(5433)의 controlplane DB 와 OPENAI_API_KEY 필요
```

컨테이너 실행은 `infra/docker-compose.yml` (전체 스택 단일 진입점). 필요한 환경 변수는 compose 의 control-plane 서비스 주석 참고.
