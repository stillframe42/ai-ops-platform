# ai-ops-platform

멀티 에이전트 기반 AIOps 플랫폼 — 데모/학습 프로젝트.

## 시스템 개요

운영 중인 서비스의 장애를 사람이 대시보드를 지켜보다 발견하는 대신, 에이전트가 감지하고 분석해서 대응까지 이어주는 플랫폼이다. 모니터링 에이전트가 메트릭 이상(latency 급증, 에러율 상승, 메모리 누수 패턴)을 감지하면, 분석 에이전트가 메트릭과 로그를 조사해 원인 보고서를 만들고 Slack 으로 알린다. 재시작·스케일아웃 같은 조치가 필요한 경우 실행 에이전트가 대응을 제안하되, 반드시 사람의 승인(human-in-the-loop)을 거친 뒤에만 실행한다. 모니터링 대상은 저장소에 포함된 데모 앱(target-app)으로, 장애를 일부러 일으키는 fault-injection 수단을 제공해 전체 시나리오를 재현 가능하게 한다.

## 목표 아키텍처

| 컴포넌트 | 책임 |
|----------|------|
| [`control-plane/`](control-plane/) | 관제/API/게이트웨이 — 에이전트 오케스트레이션 진입점과 human-in-the-loop 승인 API |
| [`agent-service/`](agent-service/) | 멀티 에이전트 — 모니터링(감지)/분석(원인 조사)/실행(조치 제안) 에이전트 |
| [`target-app/`](target-app/) | 모니터링 대상 데모 앱 — fault-injection(지연/에러율/메모리 누수) 제공 |
| [`infra/`](infra/) | 로컬 실행 인프라 — docker-compose 단일 진입점, Prometheus, Grafana |

C4 다이어그램(System Context / Container)은 [`docs/architecture.md`](docs/architecture.md)에서 관리한다. 아키텍처 결정 이력은 [`docs/adr/`](docs/adr/) 참고.

## 기술 스택

| 영역 | 선택 | 비고 |
|------|------|------|
| 관제/게이트웨이 | Kotlin + Spring Boot 4.x | control-plane, target-app |
| 에이전트 | Python + LangGraph | uv 기반, Durable Execution ([ADR-0009](docs/adr/0009-postgres-checkpointer.md)) |
| LLM 프로바이더 | Anthropic Claude Sonnet 5 | 설정으로 전환 가능 ([ADR-0007](docs/adr/0007-llm-provider.md)) |
| 도구 노출 | MCP (Streamable HTTP) | control-plane 운영 도구 → 에이전트 ([ADR-0010](docs/adr/0010-mcp-tool-exposure.md)) |
| 이벤트 파이프라인 | Kafka (KRaft) | Alert → 인시던트 → 분석 결과 ([ADR-0011](docs/adr/0011-kafka-trigger.md)) |
| 메트릭 수집 | Prometheus | Alertmanager 룰 기반 웹훅 ([ADR-0003](docs/adr/0003-alertmanager-webhook.md)) |
| 로그 | Loki + Alloy | 분석 에이전트 조회 도구 ([ADR-0004](docs/adr/0004-loki-adoption.md)) |
| 대시보드 | Grafana | |
| LLM 관측·비용 | Langfuse v3 (자가 호스팅) | 세션 = 인시던트 |
| 알림/승인 채널 | Slack | 분석 보고 알림은 incoming webhook 확정 — 승인 인터랙션 방식은 미정 (ADR-0006 예약) |
| 로컬 실행 | docker-compose | `infra/` 단일 통합 지점 ([ADR-0001](docs/adr/0001-monorepo.md)) |

## 비목표 (Non-goals)

데모/학습 프로젝트로 범위를 고정한다. 아래는 의도적으로 하지 않는다.

- **실 사용자 트래픽 없음** — 실 사용자·실 서비스를 대상으로 운영하지 않는다. K8s 배포(8월~)도 운영 설계 학습 목적이며, 부하는 자체 부하 테스트로 한정.
- **멀티 테넌시 없음** — 단일 대상(target-app), 단일 운영자를 가정한다.
- **실 서비스 대상 자동 조치 없음** — 조치 실행은 저장소 내 데모 앱에 한정하고, 그마저도 사람 승인 없이는 실행하지 않는다.
- **범용 AIOps 제품 아님** — 임의 시스템에 붙는 플러그인 구조를 지향하지 않고, 정의된 시나리오 3종의 E2E 데모를 목표로 한다.

## 로드맵

각 단계는 이전 단계의 산출물을 입력으로 삼는다.

**7월 — 코어 구축 (주 단위)**

| 주차 | 마일스톤 | 산출물 |
|------|----------|--------|
| 1주 | 설계 | 저장소 구조, README, 시나리오 정의, C4 다이어그램, ADR |
| 2주 | infra 기동 | docker-compose 로 Prometheus + Grafana 스택 기동 |
| 3주 | target-app | 데모 앱 + fault-injection 엔드포인트 (시나리오에서 역산한 요구사항 반영) |
| 4주 | 에이전트 | control-plane ↔ agent-service 연동, 모니터링/분석/실행 에이전트 구현 |
| 5주 | E2E 데모 | 시나리오 3종(latency 급증 / 에러율 급증 + 승인 조치 / 메모리 누수) 시연 |

**8월 — 클라우드 네이티브 + 보안**

| 마일스톤 | 산출물 |
|----------|--------|
| K8s 운영 설계 | 서비스별 독립 Deployment, HPA 전략(Spring Boot: CPU/메모리, 에이전트: 큐 길이), Helm Chart 패키징 — compose 와의 역할 분담은 ADR 로 결정 |
| LLM 게이트웨이 | 모델 라우팅(비용 vs 품질), Redis 의미 유사도 캐싱, Rate Limiting/비용 추적, 모델 폴백 — control-plane 내장 vs 별도 서비스는 ADR 로 결정 |
| AI 시스템 보안 | Prompt Injection 방어, 민감 로그 자동 마스킹, Spring Security + 에이전트 권한 설계, Zero Trust 도구 범위 제한 |

**9월 — Observability + 성능 최적화**

| 마일스톤 | 산출물 |
|----------|--------|
| 분산 추적 | OTel 계측(Spring Boot 자동 / Python 수동), 레이어 간 Trace Context 전파, Grafana Tempo E2E 시각화 |
| AI 전용 메트릭 | LLM-as-a-Judge 품질 평가, 에이전트 성공률/스텝 수 추적, 비용 대비 품질 대시보드 |
| 성능 최적화 | 비동기 배치 처리, 에이전트 병렬 실행, 응답 스트리밍(SSE), 부하 테스트 + 병목 개선 리포트 |
