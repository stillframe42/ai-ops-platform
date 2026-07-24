# 도구 카탈로그

> 에이전트가 사용하는 전체 도구의 위치·권한·실패 처리 일람 (DAY 17).
> 8월 "에이전트 권한 설계"의 기초 자료 — 권한 수준 분류가 OAuth 2.1 스코프 설계의 입력이 된다.

## 위치 기준 — 이원 구조인 이유

| 위치 | 기준 | 근거 |
|------|------|------|
| **로컬** (agent-service 내장) | 관측 스택(Prometheus·Loki) 직접 조회 | ADR-0002 — 관측 데이터는 표준 API 가 이미 있고 다른 소비자가 없다 |
| **MCP** (control-plane 서버) | 운영 데이터·향후 조치 실행 | ADR-0010 — 스키마 서버 단일 관리, 도구 추가 시 클라이언트 무수정 |

## 도구 일람

### 로컬 도구 (LangChain `@tool`)

| 도구 | 사용 에이전트 | 대상 | 권한 수준 | 실패 처리 |
|------|--------------|------|----------|----------|
| `get_active_alerts` | monitor | Prometheus | 읽기 전용 | 예외 → 노드 재시도(transient)·NodeFailure (DAY 13) |
| `query_prometheus` | monitor | Prometheus | 읽기 전용 | 〃 |
| `query_prometheus_range` | monitor | Prometheus | 읽기 전용 | 〃 |
| `get_app_logs` | analysis | Loki | 읽기 전용 | 〃 |
| `compare_with_baseline` | analysis | Prometheus | 읽기 전용 | 〃 |

### MCP 도구 (control-plane, `@McpTool` — Streamable HTTP + `X-API-Key`)

| 도구 | 사용 에이전트 | 대상 | 권한 수준 | 실패 처리 |
|------|--------------|------|----------|----------|
| `getDeploymentHistory` | analysis | 시드 (실 연동 범위 밖) | 읽기 전용 | 모르는 앱 → 빈 배열 |
| `searchSimilarIncidents` | analysis | pgvector (controlplane DB) | 읽기 전용 | error 필드 JSON + isError:false 강등 (DAY 13 관례) |
| `getAppConfig` | analysis | 시드 | 읽기 전용 | 모르는 앱 → error 필드 JSON |

- MCP 도구 3종은 전부 `readOnlyHint=true / destructiveHint=false / idempotentHint=true / openWorldHint=false` 로 광고 (기본값이 destructiveHint=true 라 명시 필요 — DAY 15 실측)
- 연결 실패의 두 층: 발견(tools/list) 실패 → 로컬 도구만으로 강등 완주, 호출(tools/call) 실패 → NodeFailure → 부분 보고서 (DAY 16 실측)

### 자리만 있는 도구 (미구현)

| 도구 | 예정 | 권한 수준 (예정) |
|------|------|-----------------|
| 조치 실행 (컨테이너 재시작 등) | 4주차 — ADR-0005 (실행 주체) 결정 후 | **조치 실행** — 승인(human-in-the-loop) 전제 |
| k8s 도구 (`k8s_tools.py` 자리) | 8월 K8s 주간 | 조치 실행 |

## 권한 수준 분류

| 수준 | 정의 | 현재 해당 | 8월 설계 방향 |
|------|------|----------|--------------|
| 읽기 전용 | 상태를 바꾸지 않는 조회 | 전체 8종 | API Key 수준으로 충분 |
| 조치 실행 | 대상 시스템 상태 변경 | 없음 (4주차부터) | OAuth 2.1 스코프 + 승인 흐름 필수, destructiveHint 광고 정합 |

## 관측 (DAY 17)

- Spring AI 2.0.0 MCP 모듈에는 내장 관측이 없다 (실측 — jar 전체에 Micrometer 클래스 부재) → 명시적 계측 (`McpToolMetrics.record` 로 각 도구 메서드를 감쌈 — 관측 용도에 AOP/위버 의존을 추가하지 않는 선택, 신규 도구의 계측 누락은 배선 테스트가 강제)
- 메트릭: `mcp_tool_calls_seconds_*{tool, outcome}` — outcome 은 success / **degraded**(error 필드 강등 응답 — 프로토콜 수준만 보면 실패율이 항상 0 이라 분리) / failure(예외)
- Grafana: `MCP 도구 호출 (control-plane)` 대시보드 (호출 빈도·평균 응답 시간·outcome 분포·비정상 비율)
- 로컬 도구는 별도 계측 없음 — LangChain 콜백 경유로 Langfuse 스팬에 기록됨 (DAY 14)

## 검토 기록

- **강등 사실의 보고서 기록** (DAY 16 이월 검토): MCP 발견 실패로 로컬 강등된 실행은 그래프 errors 에 남지 않아 보고서만으로 도구 구성 축소를 알 수 없다. 서버 쪽 메트릭으로도 안 보인다 (발견 실패는 서버에 도달 자체를 안 함). 상태 스키마 변경이 필요하므로 **4주차 보고서 영속화(DAY 20) 때 "사용 가능했던 도구 목록" 필드로 함께 반영**하기로 하고 이월.
