# llm-gateway

모든 LLM 호출의 단일 관문 — Spring Boot 4.x + Kotlin + Spring AI 2.0. 라우팅·캐싱·비용 통제·폴백·rate limiting 이 이 한 곳에 수렴한다 ([ADR-0015](../docs/adr/0015-llm-gateway.md)).

## 책임 (6주차 Phase 별 구축)

| # | 책임 | 상태 |
|---|------|------|
| 1 | 모델 라우팅 — `X-Task-Type` 헤더 기반 태스크별 모델 선택 | Phase 2 |
| 2 | 응답 캐싱 — 정확 일치(Redis) + 의미 유사도(pgvector) 2단계 | Phase 3 |
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

### 헤더 계약

| 헤더 | 방향 | 용도 | Phase |
|------|------|------|-------|
| `X-Task-Type` | 요청 | 라우팅 정책 키 (monitoring-summary / root-cause-analysis / ...) | 2 |
| `X-Cache-Control: no-cache` | 요청 | 캐싱 제외 (실시간 메트릭 분석 요청) | 3 |
| `X-Gateway-Cache` | 응답 | 캐시 판정 노출 (`exact_hit` / `semantic_hit` / `miss` / `bypass`) | 3 |
| `X-Client-Service` | 요청 | 비용 집계·예산·rate limit 의 서비스 차원 (부재 = `unknown`) | 4 |
| `X-Gateway-Downgrade: budget-exceeded` | 응답 | 예산 100% 도달로 저비용 모델 강제 전환됨 (응답 `model` 필드와 함께 확인) | 4 |
| `Retry-After` | 응답 (429) | 분당 한도 초과 시 재시도 대기 초 | 4 |

## 응답 캐싱 (Phase 3) — 2단계

| | 정확 일치 캐시 (exact) | 의미 유사도 캐시 (semantic) |
|---|---|---|
| 저장소 | Redis (`gw:exact:` 접두 해시 키, TTL 1시간) | pgvector `llmgateway` DB `semantic_response_cache` |
| 히트 조건 | 요청 정규화 해시 완전 일치 | 코사인 유사도 > 0.95 **+ 같은 해석 모델** |
| 조회 비용 | ~1ms | 임베딩 API 1회 (text-embedding-3-small) |

- 정확 캐시 키에는 해석된 프로바이더·모델·유효 max_tokens·temperature·메시지 전체가 들어간다 — 옵션이 다르면 다른 항목
- 의미 캐시 히트는 정확 캐시로 승격 — 같은 정확 요청의 다음 조회는 임베딩 없이 적중
- **캐시 저장소 장애 = 무캐시 통과** — 게이트웨이 가용성은 캐시에 종속되지 않는다 (Redis 헬스 인디케이터 비활성이 같은 이유)

### 캐싱 가능 분류 기준 (제외 규칙)

| 요청 | 판정 | 근거 |
|------|------|------|
| tool 정의(`tools`) 포함 | **제외 (bypass)** | 도구 실행 결과에 의존 — 같은 질문이라도 도구가 반환한 실시간 상태에 따라 응답이 달라진다 |
| tool 이력(`role: tool`·`tool_calls`) 포함 대화 | **제외 (bypass)** | 위와 동일 — ReAct 루프 중간 상태는 재사용 불가 |
| `X-Cache-Control: no-cache` | **제외 (bypass)** | 호출자가 실시간성을 선언 (예: 방금 주입된 장애의 메트릭 분석) |
| 그 외 채팅 완성 | 캐싱 | 순수 텍스트 → 텍스트 — 같은 질문이면 같은 답 재사용 가능 |
| `/v1/embeddings` | 미캐싱 | 임베딩은 결정적이라 정확 일치 캐싱이 유효하나 필수 아님 (Phase 0 ⑨ — 선택 유예) |

주의: 에이전트의 ReAct 호출은 대부분 tool 정의를 포함하므로 제외된다 — 캐시의 주 수혜 경로는 도구 없는 요약·보고 태스크와 반복 질의. 알려진 한계: 의미 캐시 항목에 만료가 없다 (TTL 은 정확 캐시만 — 데모 규모 수용, 필요 시 `cached_at` 메타데이터 기반 청소 후행).

## 비용 추적 + 예산 통제 (Phase 4)

- **단가 테이블 yml 외부화** (`gateway.cost.prices` — 접두 매칭으로 프로바이더의 날짜 접미 모델명 흡수): Sonnet 5 인트로 가격 종료(8월 말) 시 설정만 갱신. 미등록 모델은 0 계상
- **요청별 원장**: PostgreSQL `llm_cost_ledger` (`llmgateway` DB — 의미 캐시와 공용) — 서비스/태스크/모델/일별 차원, 캐시 히트는 지출 0 + 절감액(`saved_usd`) 기록. Micrometer `gateway.cost.usd`·`gateway.cost.saved.usd` 병행 (Grafana 패널 원천)
- **일별 예산** (`gateway.budget`, UTC 기준): 80% 도달 → Slack 경고 1회, **100% 도달 → 저비용 모델 강제 다운그레이드 (차단 없음)** — 장애 대응 파이프라인은 멈추지 않는다. 카운터는 Redis (`gw:budget:` — replica 2 전제 외부 저장), 카운터 장애 = 통제 없이 통과
- 발생 순서: 라우팅 해석 → 예산 판정(다운그레이드) → 캐시 → 중계 → 비용 계상·정산 — 다운그레이드된 라우트가 캐시 키·모델 필터에도 쓰여 원 모델 캐시와 격리

## Rate Limiting (Phase 4)

- Bucket4j + Redis 토큰 버킷 (`gw:rl:` — 분산 대응), `X-Client-Service` 별 분당 한도 (`gateway.ratelimit`, docker 프로파일만 활성)
- 초과 시 **429 + `Retry-After`** (OpenAI `rate_limit_error` 계약 — 클라이언트 SDK 표준 재시도가 그대로 동작)
- Redis 장애 = 통과 (fail-open — 가용성 우선, fail-closed 요건은 보안 주간 재검토)

## 모듈 구조

```
stillframe42.llmgateway
├── api/        # OpenAI 호환 표면 — DTO(계약)·컨트롤러. 형식 검증만, 정책 없음
├── relay/      # 프로바이더 중계 — Spring AI ChatModel/EmbeddingModel 호출·형식 번역
├── routing/    # 태스크 유형 → 모델 라우팅 (Phase 2 — yml 외부화 정책)
├── cache/      # 2단계 캐싱 (Phase 3 — 정확 일치 Redis + 의미 유사도 pgvector, 제외 규칙)
├── cost/       # 비용 계상·원장 (Phase 4 — 단가 외부화, PostgreSQL + Micrometer)
├── budget/     # 일별 예산 판정·정산·경고 (Phase 4 — Redis 카운터, Slack, 다운그레이드)
└── ratelimit/  # 서비스별 분당 한도 (Phase 4 — Bucket4j + Redis, 429 + Retry-After)
```

- 원칙: **상태는 전부 밖** (캐시·카운터 = Redis/PostgreSQL) — 다중 replica 가 코드 무수정으로 성립

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
