# ai-ops-platform

멀티 에이전트 기반 AIOps 플랫폼 — 데모/학습 프로젝트.

## 시스템 개요

운영 중인 서비스의 장애를 사람이 대시보드를 지켜보다 발견하는 대신, 에이전트가 감지하고 분석해서 대응까지 이어주는 플랫폼이다. 모니터링 에이전트가 메트릭 이상(latency 급증, 에러율 상승, 메모리 누수 패턴)을 감지하면, 분석 에이전트가 메트릭과 로그를 조사해 원인 보고서를 만들고 Slack 으로 알린다. 재시작·스케일아웃 같은 조치가 필요한 경우 실행 에이전트가 대응을 제안하되, 반드시 사람의 승인(human-in-the-loop)을 거친 뒤에만 실행한다. 모니터링 대상은 저장소에 포함된 데모 앱(target-app)으로, 장애를 일부러 일으키는 fault-injection 수단을 제공해 전체 시나리오를 재현 가능하게 한다.

## 아키텍처

| 컴포넌트 | 책임 |
|----------|------|
| [`control-plane/`](control-plane/) | 관제/API/게이트웨이 — Alert 수신·인시던트 발행, MCP 운영 도구 서버, 보고서 저장·조회 API, Slack 알림·승인 카드, human-in-the-loop 승인 API·조치 실행 대행 |
| [`agent-service/`](agent-service/) | 멀티 에이전트 — Supervisor 그래프가 모니터링(감지)/분석(원인 조사)/실행(조치 제안) 에이전트를 조율, 승인 대기(interrupt)·회복 확인 노드 포함 |
| [`llm-gateway/`](llm-gateway/) | LLM 게이트웨이 — 모든 LLM 호출의 단일 통과점 (OpenAI 호환 API): 태스크별 모델 라우팅, 2단계 시맨틱 캐싱, 비용 집계·예산 통제(초과 시 다운그레이드), Rate Limiting, 프로바이더 폴백 체인 ([ADR-0015](docs/adr/0015-llm-gateway.md)) |
| [`auth-server/`](auth-server/) | 인가 서버 — 서비스 간 OAuth 2.1 토큰 발급 (Client Credentials, 스코프 `ops:read`/`ops:approve`/`llm:invoke`), control-plane·llm-gateway 의 issuer ([ADR-0016](docs/adr/0016-mcp-authentication.md)) |
| [`target-app/`](target-app/) | 모니터링 대상 데모 앱 — fault-injection(지연/에러율/메모리 누수) 제공 |
| [`infra/`](infra/) | 로컬 실행 인프라 — docker-compose 단일 진입점 (Prometheus·Alertmanager·Grafana·Loki·Kafka·Langfuse·PostgreSQL) |

C4 다이어그램(System Context / Container / agent-service 내부)과 컨테이너 간 통신 프로토콜 표는 [`docs/architecture.md`](docs/architecture.md)에서 관리한다. 시나리오 정의는 [`docs/scenarios.md`](docs/scenarios.md), 아키텍처 결정 이력은 [`docs/adr/`](docs/adr/) 참고.

## 기술 스택

| 영역 | 선택 | 비고 |
|------|------|------|
| 관제/게이트웨이 | Kotlin + Spring Boot 4.x | control-plane, target-app |
| 에이전트 | Python + LangGraph | uv 기반, Durable Execution ([ADR-0009](docs/adr/0009-postgres-checkpointer.md)) |
| LLM 게이트웨이 | Kotlin + Spring Boot 4.x + Spring AI 2.0 | 별도 서비스 직접 구현 — 라우팅·캐싱(히트 시 92.2% 단축 실측)·예산·폴백 ([ADR-0015](docs/adr/0015-llm-gateway.md), LiteLLM/Bifrost 비교 포함) |
| LLM 프로바이더 | Anthropic Claude (주) + OpenAI (교차·폴백·임베딩) | 역할별 모델 차등은 게이트웨이 라우팅으로 실현 ([ADR-0007](docs/adr/0007-llm-provider.md)) |
| 도구 노출 | MCP (Streamable HTTP) | control-plane 운영 도구 → 에이전트 ([ADR-0010](docs/adr/0010-mcp-tool-exposure.md)) |
| 이벤트 파이프라인 | Kafka (KRaft) | Alert → 인시던트 → 분석 결과 ([ADR-0011](docs/adr/0011-kafka-trigger.md)) |
| 메트릭 수집 | Prometheus | Alertmanager 룰 기반 웹훅 ([ADR-0003](docs/adr/0003-alertmanager-webhook.md)) |
| 로그 | Loki + Alloy | 분석 에이전트 조회 도구 ([ADR-0004](docs/adr/0004-loki-adoption.md)) |
| 대시보드 | Grafana | |
| LLM 관측·비용 | Langfuse v3 (자가 호스팅) | 세션 = 인시던트 |
| 알림/승인 채널 | Slack | 분석 보고 알림은 incoming webhook, 승인은 Slack App — Block Kit 버튼 + Socket Mode 수신 ([ADR-0006](docs/adr/0006-slack-approval-ux.md)) |
| 로컬 실행 | docker-compose | `infra/` 단일 통합 지점 ([ADR-0001](docs/adr/0001-monorepo.md)) |

## 로컬 실행

전체 스택은 `infra/` 의 docker-compose 하나로 기동한다 ([ADR-0001](docs/adr/0001-monorepo.md)).

### 1. 사전 준비물 (.env 2곳, git 미추적)

| 파일 | 항목 | 필수 여부 |
|------|------|-----------|
| `agent-service/.env` | `ANTHROPIC_API_KEY` | **필수** — 분석 LLM 호출 |
| `infra/.env` | `SLACK_WEBHOOK_URL` / `SLACK_BOT_TOKEN` / `SLACK_APP_TOKEN` / `SLACK_APPROVAL_CHANNEL` | 선택 — 없으면 Slack 알림·승인 카드만 조용히 비활성 (승인 API 는 항상 유효) |
| `infra/.env` | `OPENAI_API_KEY` (임베딩 전용) | 선택 — 없으면 유사 인시던트 검색만 비활성 |
| `infra/.env` | `AUTH_CLIENT_SECRET_AGENT_SERVICE` / `AUTH_CLIENT_SECRET_CONTROL_PLANE` / `AUTH_CLIENT_SECRET_OPS_ADMIN` | **필수** — auth-server 클라이언트 시크릿 (ADR-0016), agent-service 는 첫 값을 `AUTH_CLIENT_SECRET` 으로 받는다 |
| `infra/.env` | `ALERTMANAGER_WEBHOOK_SECRET` | **필수** — control-plane 웹훅 공유 시크릿. compose 관측 스택은 `infra/alertmanager/webhook-secret` 파일로도 같은 값 필요 |

선택 항목은 기능 단위로 조용히 비활성되는 키-게이트 관례. 인증 관련 키는 예외로 **항상 필수** — 미설정이면 해당 서비스가 기동하지 않는다 (조용한 무인증 상태를 두지 않는 결정, ADR-0016).

### 2. 기동

```bash
cd infra
docker compose up -d postgres
# 최초 1회 — 공유 postgres 에 DB 분리 생성 (Langfuse / control-plane)
docker exec postgres createdb -U aiops langfuse
docker exec postgres createdb -U aiops controlplane
docker compose up -d
```

### 3. 데모 (시나리오 2 — 에러율 급증 + 승인 조치)

```bash
# chaos 주입: 요청의 30% 를 5xx 로 (k6 상시 트래픽이 표본을 채운다)
curl -X POST 'http://localhost:8080/chaos/error-rate?percent=30'
```

3분 지속 후 Alert 발화 → 인시던트 발행 → 분석 → Slack 승인 카드가 도착한다. [승인] 클릭 시 control-plane 이 조치를 대행 실행하고, 회복 확인 후 종결 보고가 스레드로 온다. Slack 미연동 환경은 승인 API 로 대신한다:

```bash
curl -s http://localhost:8081/api/incidents                          # 인시던트·승인 대기 확인
curl -X POST http://localhost:8081/api/incidents/{incidentId}/approve # 또는 /reject
curl -X POST http://localhost:8080/chaos/reset                        # 데모 후 원복
```

### 4. 관측 UI (호스트 포트)

| 포트 | 서비스 | 용도 |
|------|--------|------|
| 8080 | target-app | 데모 대상 · chaos 주입 |
| 8081 | control-plane | 보고서·승인 API · MCP 서버 |
| 8000 | agent-service | 인시던트 상태·히스토리 API (수동 트리거는 디버그용) |
| 8090 | llm-gateway | LLM 중계 (OpenAI 호환) · 캐시/폴백 헤더 확인 · `/actuator/prometheus` |
| 8091 | auth-server | 토큰 발급 `POST /oauth2/token` · JWKS `/oauth2/jwks` |
| 3002 | Grafana | 메트릭·로그 대시보드 |
| 9091 / 9093 | Prometheus / Alertmanager | 룰·Alert 상태 확인 |
| 3003 | Langfuse | LLM 트레이스·비용 (세션 = 인시던트) |
| 8082 | kafka-ui | 토픽·오프셋 관찰 |

## K8s 배포 (kind)

K8s(kind + Helm umbrella)가 운영 형상의 표준이고, 위의 compose 는 개발용이다 ([ADR-0013](docs/adr/0013-k8s-migration.md)). 사전 준비물은 `.env` 2곳(위 표와 동일) + `brew install kind helm kubernetes-cli`.

### 빈 클러스터 → 전체 복원 (실측 약 3분 30초)

```bash
# 1. 클러스터 생성 (control-plane 1 + worker 2)
kind create cluster --config infra/k8s/kind-config.yaml

# 2. 이미지 빌드 + 클러스터 반입 (레지스트리 없음 — kind load)
docker build -t aiops/target-app:local target-app/
docker build -t aiops/control-plane:local control-plane/
docker build -t aiops/agent-service:local agent-service/
docker build -t aiops/llm-gateway:local llm-gateway/
docker build -t aiops/auth-server:local auth-server/
kind load docker-image --name aiops aiops/control-plane:local aiops/agent-service:local aiops/target-app:local aiops/llm-gateway:local aiops/auth-server:local

# 3. Secret 반입 (.env 2곳 → K8s Secret, 값 미출력 — 확정 방식: Secret 직접 생성 + values 미기록, ADR-0016)
./infra/k8s/create-secrets.sh

# 4. 전체 설치 — umbrella 한 번으로 앱 6종(llm-gateway·auth-server 포함) + DB/Kafka/Redis + 모니터링·로그
helm dependency build charts/aiops
helm install aiops charts/aiops -n aiops --create-namespace -f charts/aiops/values-local.yaml
```

`kubectl -n aiops get pods` 로 전체 Running 확인 후, 데모 절차는 로컬 실행의 3번과 동일하다 — 접근만 port-forward 로 바꾼다 (호스트 포트는 compose 관례와 동일):

```bash
kubectl -n aiops port-forward svc/target-app 8080:8080       # chaos 주입
kubectl -n aiops port-forward svc/control-plane 8081:8080    # 승인 API
kubectl -n aiops port-forward svc/monitoring-grafana 3002:80     # Grafana (namespace 는 aiops 단일 — ADR-0013)
```

검증 포인트: 분석 진행 중 `kubectl -n aiops delete pod -l app=agent-service` 를 실행해도 새 pod 가 체크포인트에서 재개해 무유실 완주한다 (Durable Execution × K8s — [ADR-0009](docs/adr/0009-postgres-checkpointer.md)·[ADR-0013](docs/adr/0013-k8s-migration.md)).

### 선택: KEDA 오토스케일링 ([ADR-0014](docs/adr/0014-autoscaling-strategy.md))

```bash
helm repo add kedacore https://kedacore.github.io/charts
helm install keda kedacore/keda --version 2.20.2 -n keda --create-namespace
helm upgrade aiops charts/aiops -n aiops -f charts/aiops/values-local.yaml --set agent-service.keda.enabled=true
```

인시던트가 몰리면 ops.incidents lag 기반으로 agent-service 가 1→3(파티션 수 상한)으로 스케일아웃되고, 소진 후 1 로 복귀한다.

## 비목표 (Non-goals)

데모/학습 프로젝트로 범위를 고정한다. 아래는 의도적으로 하지 않는다.

- **실 사용자 트래픽 없음** — 실 사용자·실 서비스를 대상으로 운영하지 않는다. K8s 배포(2026-08~)도 운영 설계 학습 목적이며, 부하는 자체 부하 테스트로 한정.
- **멀티 테넌시 없음** — 단일 대상(target-app), 단일 운영자를 가정한다.
- **실 서비스 대상 자동 조치 없음** — 조치 실행은 저장소 내 데모 앱에 한정하고, 그마저도 사람 승인 없이는 실행하지 않는다.
- **범용 AIOps 제품 아님** — 임의 시스템에 붙는 플러그인 구조를 지향하지 않고, 정의된 시나리오 3종의 E2E 데모를 목표로 한다.

## 로드맵

각 단계는 이전 단계의 산출물을 입력으로 삼는다.

**7월 — 코어 구축 (완료, 주 단위 실적)**

| 기간 | 마일스톤 | 산출물 |
|------|----------|--------|
| 7/13~14 | 설계 + 관측 기반 | 시나리오 3종 정의·C4·초기 ADR, target-app + fault-injection, Prometheus/Grafana/Alertmanager/Loki 스택 |
| 7/16~21 | 멀티 에이전트 코어 | Supervisor StateGraph + 모니터링/분석/실행 에이전트, 하이브리드 라우팅 ([ADR-0008](docs/adr/0008-hybrid-routing.md)), Durable Execution ([ADR-0009](docs/adr/0009-postgres-checkpointer.md)) |
| 7/23~28 | 자동 파이프라인 | MCP 도구 노출 ([ADR-0010](docs/adr/0010-mcp-tool-exposure.md)), Kafka 트리거 ([ADR-0011](docs/adr/0011-kafka-trigger.md)) — chaos 주입부터 Slack 보고까지 사람 개입 없음, 장애 주입 실측 (다운 중 무유실) |
| 7/31~8/7 | human-in-the-loop | 승인 도메인 + Slack 승인 카드/Socket Mode ([ADR-0006](docs/adr/0006-slack-approval-ux.md)) + 조치 실행 대행 ([ADR-0005](docs/adr/0005-action-executor.md)) + 회복 확인 — 승인·거부·타임아웃 3경로 실측 (Alert 발화→종결 약 1분 54초) |

**8월 — 클라우드 네이티브 + 보안**

| 마일스톤 | 산출물 |
|----------|--------|
| K8s 운영 설계 (완료 — 8/10~14) | kind 3노드 + Helm umbrella 9종 차트 (빈 클러스터→전체 복원 3분 24초 실측, [ADR-0013](docs/adr/0013-k8s-migration.md) — compose 는 개발용 유지), Durable Execution × pod 강제 삭제 무유실 실측, KEDA lag 기반 스케일링 (10건 동시 주입 무유실, [ADR-0014](docs/adr/0014-autoscaling-strategy.md) — CPU 는 LLM 워크로드의 수요 신호가 아님) |
| LLM 게이트웨이 (완료 — 8/17~22) | 별도 서비스 직접 구현 ([ADR-0015](docs/adr/0015-llm-gateway.md) — LiteLLM/Bifrost 비교표 포함): 모든 LLM 호출 단일 경유 (OpenAI 호환), 태스크별 모델 라우팅, 2단계 시맨틱 캐싱 L1 Redis + L2 pgvector (히트 시 응답 92.2% 단축 실측), 비용 집계·일별 예산 (100% = 차단 아닌 다운그레이드)·Rate Limiting, 프로바이더 폴백 체인 + 서킷 (키 무효화 실측 — 교차 프로바이더 정상 응답), replica 2 + PDB, 전용 대시보드 13패널 + W3C trace 전파 |
| AI 시스템 보안 (완료 — 8/24~9/2) | OAuth 2.1 M2M 인증 — auth-server 발급(Client Credentials) + 리소스 서버별 동일 issuer 검증, 스코프 `ops:read`/`ops:approve`/`llm:invoke`, 전 M2M 호출 감사 로그 ([ADR-0016](docs/adr/0016-mcp-authentication.md)) · Prompt Injection 계층 방어 4종 (구조적 분리·입력 가드레일·도구 인자 검증·출력/저장 스캔+마스킹, [ADR-0017](docs/adr/0017-prompt-injection-defense.md)) · 레드팀 20종 회귀 스위트 — baseline 뚫림 8 → 0, 계층별 결정론 테스트 CI 고정 |

**9월 — Observability + 성능 최적화**

| 마일스톤 | 산출물 |
|----------|--------|
| 분산 추적 | OTel 계측(Spring Boot 자동 / Python 수동), 레이어 간 Trace Context 전파, Grafana Tempo E2E 시각화 |
| AI 전용 메트릭 | LLM-as-a-Judge 품질 평가, 에이전트 성공률/스텝 수 추적, 비용 대비 품질 대시보드 |
| 성능 최적화 | 비동기 배치 처리, 에이전트 병렬 실행, 응답 스트리밍(SSE), 부하 테스트 + 병목 개선 리포트 |
