# ADR-0016: MCP 인증 — 서버별 검증 vs 게이트웨이 중앙 검증

- 상태: 승인됨 (초안 2026-08-25 → 구현·클러스터 실측 후 2026-08-28 확정)
- 날짜: 2026-08-25

## 맥락

agent-service → control-plane(MCP 도구)·llm-gateway(LLM 호출)의 서비스 간 호출은 DAY 16 의 임시 `X-API-Key` 헤더(MCP 만)와 무인증(게이트웨이 — `X-Client-Service` 자기 신고)으로 운영되어 왔다. 보안 주간의 목표는 이를 OAuth 2.1 기반으로 바꾸는 것인데, 세 가지를 함께 결정해야 한다 — ① 토큰 발급자를 어디에 둘지 ② 토큰을 누가 검증할지 ③ 권한을 어떻게 나눌지. 결정 입력은 [위협 모델](../security/threat-model.md)(승인 API 무인증·헤더 위조가 직접 경로), `docs/tools-catalog.md` 권한 수준 분류(읽기 전용 / 조치 실행), [ADR-0005](0005-action-executor.md) 재평가 조건(RBAC 최소 권한 설계와 함께 자동 실행 복귀 최종 결정), [ADR-0015](0015-llm-gateway.md) 가 기각한 "게이트웨이 경유 MCP"(순환 결합).

제약: 호출 주체는 전부 서비스(M2M)라 사용자 대리 플로우가 없다. 승인자는 Slack Socket Mode 경유라 인바운드 엔드포인트가 없다. 에이전트 실행은 승인 대기로 수 시간 중단될 수 있어 토큰 수명과 세션 길이가 어긋난다.

## 결정

**발급 = 별도 `auth-server`** (Spring Boot 4 + Spring Security 7 통합 Authorization Server, Client Credentials 전용). **검증 = 각 리소스 서버가 동일 issuer 의 JWKS 로 자체 검증** (control-plane·llm-gateway 각각 OAuth2 리소스 서버). **권한 = 스코프 3종** `ops:read`(MCP 조회 도구·조회 API) / `ops:approve`(승인 API) / `llm:invoke`(게이트웨이). agent-service 는 `ops:read`+`llm:invoke` 만 보유해 승인 권한이 구조적으로 없다. 토큰은 단일 JWT 에 스코프 접두로 도출한 다중 `aud`(`control-plane`·`llm-gateway`), 수명 15분, Client Credentials 는 refresh token 이 없으므로(RFC 6749 §4.4.3) 갱신은 **재발급** — 클라이언트는 만료 60초 전 재발급 + 401 시 1회 재시도로 "호출 시점 유효"를 보장한다. 인증은 **항상 필수** — issuer 미설정은 기동 실패이며 로컬 개발도 auth-server 를 전제한다 (compose 반영). Alertmanager 웹훅만 OAuth 클라이언트가 아니라 공유 시크릿으로 예외.

## 검토한 대안

| 대안 | 장점 | 단점 | 기각 사유 |
|------|------|------|-----------|
| control-plane 에 인가 서버 내장 | 배포 단위 추가 없음 | llm-gateway 가 control-plane 의 JWKS 에 의존 — 게이트웨이 → 관제 의존 | ADR-0015 가 기각한 순환 결합의 재발, 발급자·검증자 경계가 코드에 안 드러남 |
| 기성 IdP (Keycloak) | 실무 표준 | kind 3노드에 무거움, 설정이 UI 에 남아 코드 재현 불가 | 학습 목표(Spring Security 7 AS 통합)와 불일치 |
| 게이트웨이 중앙 검증 + 신뢰 헤더 전파 (원안) | 검증 코드 1곳 | llm-gateway 가 MCP 까지 프록시하는 API 게이트웨이가 되어야 함 — 책임 팽창·경로 재배선·헤더 위조 방어(내부 서명/mTLS) 직접 구현·게이트웨이 장애 = MCP 단절 | JWT 자체가 서명된 identity 라 "한 번 검증 후 전파"가 새로 만드는 신뢰 문제가 더 큼 |
| mcp-authorization-server 확장 채택 | MCP 인가 스펙(DCR·Resource Indicators) 지원 | DCR 은 M2M 에서 미사용 공격 표면 | aud 바인딩은 표준 토큰 커스터마이저로 충분 |
| 조치 실행 MCP 도구 + `ops:action` (원안) | 원안 문구 그대로 | 조치 도구 실물이 없고, 만들면 HITL 경로와 이중화 | ADR-0005 구조 훼손 — 보호 대상은 "승인 결정"이라 `ops:approve` 로 명명 |
| 키-게이트 승계 (issuer 미설정 = 인증 비활성) | 로컬 무설정 기동 유지 | 보안 기본값 off | 사용자 결정: 항상 필수 — 비활성 상태 자체를 두지 않는다 |

## 결과

- 쉬워지는 것: 게이트웨이의 서비스 식별(예산·rate limit 차원)이 검증된 `client_id` 로 바뀐다. 승인 API 무인증이 해소된다. "에이전트는 승인할 수 없다"가 토큰 실물로 검증 가능하다 (OWASP LLM06).
- 어려워지는 것: Boot 앱 4종째(배포 단위 +1). 로컬 실행·테스트가 토큰 전제 — Spring 테스트는 `spring-security-test` 의 `jwt()` 로 issuer 없이, agent-service 는 compose 의 auth-server 로.
- 남는 것: 서명 키는 기동 시 생성(replica 1) — replica 2 는 고정 키 Secret 반입 필요. client secret 은 `{noop}` 평문 대조 — bcrypt 해시 저장은 Secret 관리 심화와 함께 재검토. 감사 로그의 요청 간 상관은 `traceId`(tracing MDC) 로 — 토큰 재발급 자체는 로그 1행(`액세스 토큰 발급`)으로만 남고 별도 상관 필드는 두지 않는다.

## 추가 사항 (2026-08-28): 구현 확정 — 두 번째 리소스 서버·클라이언트 토큰 부착·감사 로그·Secret 관리

구현·클러스터 실측(2026-08-26 control-plane, 2026-08-28 llm-gateway)으로 "제안됨"을 "승인됨"으로 올리며 아래를 확정한다.

- **검증 위치 = 리소스 서버별 자체 검증** 그대로 — llm-gateway 도 `spring-boot-starter-oauth2-resource-server` 만으로
  같은 issuer·`audiences: llm-gateway`·`SCOPE_llm:invoke` 로 전환했다. 중앙 검증·신뢰 헤더 전파(`X-Authenticated-Service`·`X-Scopes`)는
  구현하지 않았다 — 두 리소스 서버의 설정 관례(issuer-uri + audiences + 경로별 `hasAuthority`)가 같아 "공통 설정 명문화"로 충분했다.
- **서비스 식별 = JWT `sub`(client_id)** — 게이트웨이의 `X-Client-Service` 자기 신고 헤더를 제거하고 예산·rate limit·비용 원장의
  service 차원을 검증된 client_id 로 바꿨다 (위협 모델 ⑧ 해소). 등록명이 서비스명과 같아 한도 설정 키는 무수정.
- **클라이언트 토큰 부착 방식** — agent-service 는 `httpx.Auth`(`ClientCredentialsAuth`) 하나를 MCP 연결과 게이트웨이용 `ChatOpenAI`
  의 `http_client`·`http_async_client` 에 공유 주입 (토큰 1개 = aud 2개, 동기·비동기 flow 둘 다). control-plane 은 계획의
  "RestClient 인터셉터"가 적용 불가였다 — Spring AI 2.0 의 OpenAI 클라이언트가 공식 openai-java SDK(OkHttp) 라 `RestClient`
  를 쓰지 않는다. `OpenAiHttpClientBuilderCustomizer.interceptor(okhttp3.Interceptor)` 훅에 `GatewayTokenInterceptor` 를 달고,
  발급·캐시·만료 60초 전 재발급(clockSkew 기본값)은 Spring Security `OAuth2AuthorizedClientManager`(client_credentials) 에 맡겼다.
  두 클라이언트 모두 401 수신 시 재발급 후 1회 재시도. control-plane 의 OPENAI_API_KEY 의존은 제거 (프로바이더 키는 게이트웨이만 보유).
- **감사 로그** — 로거명 `audit`, 필드는 MDC → ECS JSON 최상위 필드, `traceId` 동반. control-plane `mcp_request`(서블릿 필터 —
  도구 본체가 MCP 서버의 별도 스레드에서 실행될 수 있어 SecurityContext 가 확실한 서블릿 스레드에서 JSON-RPC 본문만 읽어 기록) ·
  `approval_decision` · `action_execution`, llm-gateway `gateway_request`. 인시던트 컨텍스트는 승인·조치 행에는 `incident_id` 로,
  MCP·게이트웨이 행에는 `traceId` 상관으로 (agent-service 가 인시던트별 trace 를 시작한다).
- **Secret 관리 (⑬ 확정)** — K8s Secret 직접 생성(`create-secrets.sh`) + 차트 values 미기록 + compose `.env` pass-through 를
  확정 방식으로 한다. 외부 Secret 매니저(External Secrets Operator 등)는 단일 kind 클러스터·운영자 1인 규모에서 관리 대상이
  늘기만 해 범위 밖. client secret 의 bcrypt 저장·서명 키 고정(replica 2)은 재평가 조건에 남긴다.
- **ADR-0005 재평가 결론** — RESTART_APP 자동 실행 복귀는 **이번에도 하지 않는다**. 스코프 모델이 보호하는 것은 "승인 결정"이며
  (`ops:approve`), 조치 실행 권한(control-plane ServiceAccount 의 deployments patch)은 OAuth 스코프와 무관한 K8s RBAC 문제라
  이 ADR 의 범위 밖이다. 판단 근거 ③(파급 있는 조치는 사람의 자각 아래)이 잔존하는 한 수동 안내 유지 — ADR-0005 에 추가 사항으로 기록.

## 재평가 조건

- 사용자 대리 플로우(사람 로그인)가 생기면 Authorization Code + PKCE 와 DCR 필요성을 재검토한다.
- 서비스 메시·인그레스 외부 인가가 도입되면 "리소스 서버별 검증"을 사이드카 검증으로 옮길지 재검토한다.
- auth-server replica 2 또는 무중단 재기동 요건이 생기면 서명 키를 Secret 고정으로 옮기고 client secret 을 bcrypt 로 저장한다.
- Spring AI 가 OpenAI 클라이언트에 `ApiKey` 공급자(호출 시점 평가)를 자동구성으로 노출하면 OkHttp 인터셉터를 그 경로로 대체한다.
