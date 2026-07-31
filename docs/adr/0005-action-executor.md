# ADR-0005: 조치 실행 주체 — control-plane 대행

- 상태: 승인됨
- 날짜: 2026-07-31

## 맥락

실행 에이전트는 3주차까지 조치 계획(ActionPlan) 생성만 담당했다 (도구 없음 — `action_agent.py`). 4주차 human-in-the-loop 에서 실제 조치(대상 앱 재시작, chaos 해제)를 연결하려면 docker 데몬 접근이 필요한데, 이 권한을 누구에게 줄 것인가가 미결이었다 (scenarios.md ADR-0005 예약).

결정을 미룰 수 없는 이유: 승인 왕복 경로(그래프 interrupt-resume 위치, `ops.actions.pending` 의 발행/소비 주체)와 승인 테이블 스키마가 이 결정에 종속된다.

## 결정

**control-plane 이 조치 실행을 대행한다.** agent-service 는 조치안을 `ops.actions.pending` 으로 발행만 하고, 실행 권한(docker 접근)은 갖지 않는다.

- 근거 1 — 권한 경계: LLM 프로세스(agent-service)에 인프라 변경 권한을 부여하지 않는다. 프롬프트 주입 등으로 에이전트가 오염돼도 도달 범위가 읽기(Prometheus/Loki/MCP 읽기 도구)에 그친다. 도구 카탈로그의 "전부 읽기 전용" 분류(3주차)가 유지된다.
- 근거 2 — 수렴: 승인 판단(사람)·감사 기록·실행이 관제 계층 한 곳에 모인다. 승인 없이 실행에 도달하는 코드 경로가 구조적으로 없다.
- docker 접근 방식: control-plane 컨테이너에 docker socket 마운트 (호스트 데몬 권한 — 데모 범위로 수용, 8월 보안 주간에서 재검토 대상으로 기록).

### 승인 왕복 배선 (이 결정의 상세)

```
agent-service                          control-plane                      사람
action_node (계획 생성)
  → approval_node: ops.actions.pending 발행
     + interrupt (체크포인트 영속 대기)
                                       ActionsPendingConsumer
                                         → action_approvals 행 (pending)
                                         → Slack 승인 요청        →      [승인]/[거부]
                                       승인 API (상태 전이 + 감사 기록)
                                         → (approved) 조치 실행
                                         → ops.actions.decisions 발행 (신규 토픽)
decisions 컨슈머
  → runtime.resume(thread_id, 결정)
  → 모니터링 재확인 (회복 판정) → 종결 보고
```

- `ops.actions.decisions` 토픽 신설 (파티션 1, key=incident_id, 보존 7일 — kafka-init 명시 생성 관례)
- 승인 처리 로직은 승인 API 한 곳으로 수렴 — Slack 은 입력 채널일 뿐 (ADR-0006)
- 실행 결과(성공/실패)는 decisions 페이로드에 포함 — 그래프 재개 후 회복 판정의 입력

## 검토한 대안

| 대안 | 장점 | 단점 | 기각 사유 |
|------|------|------|-----------|
| agent-service 직접 실행 | 그래프 안에서 승인 대기→실행이 완결 — 배선 단순 (토픽 왕복 불요) | LLM 프로세스에 docker 권한 — 오염 시 인프라 도달, 감사·실행 지점 분산 | 권한 경계가 데모여도 지켜야 할 핵심 학습 소재 — 보안 경계를 이유로 기각 |
| 실행 전용 사이드카 분리 | 권한 최소화의 이상형 | 구성요소·배포 단위 추가, 승인 로직과 실행의 분리로 왕복 1회 증가 | 데모 범위 과잉 — control-plane 이 이미 관제 역할, 8월 K8s 전환 시 재검토 |

## 결과

- 쉬워지는 것: 승인·감사·실행의 단일 책임 지점. agent-service 의 읽기 전용 유지. 조치 이력이 `action_approvals` 한 테이블로 남는다.
- 어려워지는 것: 왕복에 토픽 2개(`pending`/`decisions`) — 신규 토픽 1개의 kafka-init 갱신, agent-service 에 컨슈머 1개 추가. 회복 확인 루프는 그래프 재개 쪽 책임으로 남는다.
- 되돌리려면: 실행 로직을 agent-service 도구로 이동하고 decisions 토픽을 제거 — 승인 API·테이블은 재사용 가능.
