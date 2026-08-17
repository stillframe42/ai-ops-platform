# llm-gateway

모든 LLM 호출의 단일 관문 — Spring Boot 4.x + Kotlin + Spring AI 2.0. 라우팅·캐싱·비용 통제·폴백·rate limiting 이 이 한 곳에 수렴한다 ([ADR-0015](../docs/adr/0015-llm-gateway.md)).

## 책임 (6주차 Phase 별 구축)

| # | 책임 | 상태 |
|---|------|------|
| 1 | 모델 라우팅 — `X-Task-Type` 헤더 기반 태스크별 모델 선택 | Phase 2 |
| 2 | 응답 캐싱 — L1 정확 일치(Redis) + L2 의미 유사도(pgvector) | Phase 3 |
| 3 | 비용 추적 + 예산 통제 — 중앙 집계·초과 시 저비용 모델 다운그레이드 | Phase 4 |
| 4 | 폴백 — Anthropic 장애 시 OpenAI 전환 (Resilience4j) | Phase 5 |
| 5 | Rate Limiting — Bucket4j + Redis, 서비스별 한도 | Phase 4 |
| 6 | 입출력 가드레일 + 인증 전파 — 확장 지점만 확보 | 7주차 (보안 주간) |

## API — OpenAI 호환

클라이언트는 base-url 을 게이트웨이로 바꾸는 것으로 전환된다 (클라이언트 로직 무수정).

### `POST /v1/chat/completions`

```json
{
  "model": "claude-sonnet-5",
  "messages": [{"role": "user", "content": "..."}],
  "max_tokens": 2000,
  "temperature": 0.7
}
```

- `model`·`max_tokens`·`temperature` 부재 시 서버 기본값 (claude-sonnet-5 / 2000)
- `role` 은 system / user / assistant 3종
- **스트리밍 미지원** — `stream: true` 는 400 (Phase 0 결정: 현행 클라이언트 사용 0건 실측, 배제가 아닌 유예)
- 응답: OpenAI `chat.completion` 형태 (`choices[].message`, `usage.prompt_tokens` 등). `finish_reason` 은 프로바이더 원문 (Anthropic: `end_turn`) — 표준값 매핑은 Phase 5 폴백과 함께

### `POST /v1/embeddings`

```json
{"input": "문자열 또는 문자열 배열", "model": "text-embedding-3-small"}
```

- control-plane 유사 인시던트 검색의 임베딩 호출이 경유 (Phase 0 결정 — "모든 LLM 호출 단일 통과점"의 완전성)

### 예정 헤더 계약

| 헤더 | 용도 | Phase |
|------|------|-------|
| `X-Task-Type` | 라우팅 정책 키 (monitoring-summary / root-cause-analysis / ...) | 2 |
| `X-Cache-Control: no-cache` | 캐싱 제외 (실시간 메트릭 분석 요청) | 3 |
| `X-Client-Service` | 비용 집계·rate limit 의 서비스 차원 | 4 |

## 모듈 구조

```
stillframe42.llmgateway
├── api/      # OpenAI 호환 표면 — DTO(계약)·컨트롤러. 형식 검증만, 정책 없음
├── relay/    # 프로바이더 중계 — Spring AI ChatModel/EmbeddingModel 호출·형식 번역
└── (예정) routing/ caching/ cost/ ratelimit/   # Phase 2~5 — relay 앞뒤의 정책 계층
```

- 원칙: **상태는 전부 밖** (캐시·카운터 = Redis/PostgreSQL) — 다중 replica 가 코드 무수정으로 성립
- 프로바이더 선택은 현재 yml 자동구성 속성 (`spring.ai.model.chat: anthropic`) — Phase 2 라우팅에서 양쪽 ChatModel 수동 Bean 으로 전환

## 실행

```bash
./gradlew bootRun    # 기본 포트 8090 (충돌 없음: target-app 8080, control-plane 8081, agent 8000)
```

- 헬스체크: http://localhost:8090/actuator/health
- 메트릭: http://localhost:8090/actuator/prometheus
- API 키: `ANTHROPIC_API_KEY`(채팅)·`OPENAI_API_KEY`(임베딩) — 빈 값이어도 기동은 되고 호출 시점에만 실패 (키-게이트 관례)

## 테스트

```bash
./gradlew test
```
