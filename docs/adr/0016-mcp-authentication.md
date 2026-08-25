# ADR-0016: MCP 인증 — 서버별 검증 vs 게이트웨이 중앙 검증

- 상태: 제안됨 (초안 2026-08-25 — 인증 구현·실측 후 확정 예정)
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
- 남는 것: 서명 키는 기동 시 생성(replica 1) — replica 2 는 고정 키 Secret 반입 필요. client secret 은 `{noop}` 평문 대조 — bcrypt 해시 저장은 Secret 관리 심화와 함께 재검토. 승인 대기 전후 스팬 연결처럼 토큰 재발급 시점의 감사 로그 상관은 감사 로그 체계 구축 시 다룬다.

## 재평가 조건

- 사용자 대리 플로우(사람 로그인)가 생기면 Authorization Code + PKCE 와 DCR 필요성을 재검토한다.
- 서비스 메시·인그레스 외부 인가가 도입되면 "리소스 서버별 검증"을 사이드카 검증으로 옮길지 재검토한다.
