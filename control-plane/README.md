# control-plane

관제/API/게이트웨이 — Spring Boot 4.x + Kotlin. 에이전트 오케스트레이션 진입점과 human-in-the-loop 승인 API 를 담당한다.

## 현재 제공 기능 (DAY 15~16)

- **MCP 도구 서버** (Spring AI 2.0, Streamable HTTP) — 운영 도구 3종을 표준 프로토콜로 노출
  - `getDeploymentHistory(app)` — 최근 배포 이력 (시드)
  - `searchSimilarIncidents(symptom)` — 과거 유사 인시던트 벡터 검색 (pgvector + OpenAI 임베딩)
  - `getAppConfig(app)` — 앱 런타임 설정 정보 (시드)
- **/mcp API Key 인증** (DAY 16) — `X-API-Key` 헤더, 키는 env `MCP_API_KEY`
  (미설정 시 인증 생략 — 로컬 개발 편의. OAuth 2.1 전환은 8월 보안 주간).
  MCP Inspector 로 호출할 때는 `--header "X-API-Key: <키>"` 필요

## 실행

```bash
./gradlew test          # 단위 테스트 (실 DB·임베딩 API 무의존)
./gradlew bootRun       # 로컬 실행 — postgres(5433)의 controlplane DB 와 OPENAI_API_KEY 필요
```

컨테이너 실행은 `infra/docker-compose.yml` (전체 스택 단일 진입점). 필요한 환경 변수는 compose 의 control-plane 서비스 주석 참고.
