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
- (구현 상세, 2026-08-04) "실행 후 발행"의 실체는 결정 상태별 비대칭 배선: rejected/expired 는
  decide 트랜잭션 안에서 즉시 발행 (발행 접수 실패 = 롤백, 재개 신호 유실 방지 규약 유지),
  approved 는 전이만 커밋하고 AFTER_COMMIT 실행 리스너가 조치 실행 → 실행 결과를 담아 발행.
  수십 초짜리 실행을 트랜잭션 안에 둘 수 없어서다. 실행 후의 발행 실패는 롤백할 본체가 없다
  (실행은 물리적 사실) — ERROR 로그 + 수동 재발행 대상 (ADR-0011 비동기 한계와 같은 계열).
  자동 실행은 CIRCUIT_BREAK(chaos/reset 호출) 1종 — RESTART_APP 은 수동 조치 안내로 전환
  (아래 추가 사항), SCALE_OUT/ROLLBACK 은 실행기가 없어 명시적 실패로 기록된다.

## 검토한 대안

| 대안 | 장점 | 단점 | 기각 사유 |
|------|------|------|-----------|
| agent-service 직접 실행 | 그래프 안에서 승인 대기→실행이 완결 — 배선 단순 (토픽 왕복 불요) | LLM 프로세스에 docker 권한 — 오염 시 인프라 도달, 감사·실행 지점 분산 | 권한 경계가 데모여도 지켜야 할 핵심 학습 소재 — 보안 경계를 이유로 기각 |
| 실행 전용 사이드카 분리 | 권한 최소화의 이상형 | 구성요소·배포 단위 추가, 승인 로직과 실행의 분리로 왕복 1회 증가 | 데모 범위 과잉 — control-plane 이 이미 관제 역할, 8월 K8s 전환 시 재검토 |

## 추가 사항 (2026-08-04): RESTART_APP 자동 실행 제외 — 수동 조치 안내로 전환

E2E 실측(승인 → docker restart 자동 실행 → 회복 확인 완주) 직후 사용자 결정으로 범위를 조정한다.

- 근거: ① 단일 인스턴스 재시작은 다운타임·진행 중 요청 유실이 실재 — 자동 실행이 또 하나의
  장애가 될 수 있다 (K8s rolling restart 같은 무중단 수단 부재) ② docker socket ≈ 호스트 root
  권한 — 본문 결정의 "데모 수용" 유보를 감수할 이득이 없다 ③ 파급 있는 조치는 버튼 클릭
  한 번보다 무거운 자각(직접 실행) 아래 두는 것이 실무 감각에 맞다.
- 전환 내용: 카탈로그·LLM 제안·승인 카드에는 RESTART_APP 유지 (진단 가치 보존). 승인 시
  실행기는 실행 대신 **수동 조치 안내**(명령 예시 포함)를 카드 스레드·감사 기록에 남긴다 —
  decisions 페이로드의 execution 항목에 `manual` 표식. 플랫폼의 역할을 "대신 실행"에서
  "감지→분석→판단 재료 준비를 압축해 사람의 조치를 빠르게"로 재정의하는 조정.
- 회복 확인: 수동 조치 뒤의 Alert 해소를 그대로 관측한다 (조치 주체는 판정에 불요) — 대기
  예산만 사람 손 기준 600s 로 확장, 시한 초과는 "수동 조치 대기"로 정직하게 종결 (안정화
  보고 불가를 숨기지 않고 보고서가 말하게 한다 — 수용된 트레이드오프).
- 부수 효과: compose 의 docker socket 마운트·이미지 내 docker CLI 제거 — 본문의 "8월 보안
  주간 재검토" 대상이 선제 해소됐다.
- 재평가 조건: 8월 K8s 전환으로 rolling restart 등 무중단 재시작 수단이 생기면 자동 실행
  후보로 복귀 검토.

## 추가 사항 (2026-08-13): RESTART_APP 재평가 — 복귀 가능 판정, 구현은 보안 주간 결합

재평가 조건(K8s 전환) 도달로 검토를 수행했다 (5주차 Phase 4, `kubectl rollout restart` 기준).

- 2026-08-04 기각 근거 3건의 현재 상태: ① 다운타임 — **해소** (`rollout restart` 는 새 pod
  Ready 후 구 pod 종료 — probe 가 무중단의 전제, Phase 3 배선 완료) ② 권한 표면 — **완화**
  (docker socket ≈ 호스트 root 였던 것과 달리, K8s 는 Role 로 "aiops namespace 의
  deployments/target-app patch" 한 줄 위임이 가능 — 좁은 범위 위임이 성립) ③ 파급 있는
  조치는 사람의 자각 아래 — **잔존** (실행 수단이 바뀌어도 판단의 무게는 불변).
- 결정: **자동 실행 복귀는 가능 판정하되 지금 구현하지 않는다** — control-plane 에
  ServiceAccount·Role 을 부여하는 일은 8월 보안 주간의 권한 체계화(Secret 관리 재검토와
  같은 묶음) 범위라, 임시 default SA 에 변이 권한을 부여하지 않는다 (임시 Secret 반입과 같은
  부채를 늘리지 않는 판단). 그때까지 수동 조치 안내 유지 — 안내 명령 예시는 K8s 형상에
  맞춰 `kubectl rollout restart` 로 갱신하는 것만 선반영 대상.
- 재평가 조건(갱신): 보안 주간에 RBAC 최소 권한 설계와 함께 자동 실행 복귀를 최종 결정
  (근거 ③의 수용 여부 포함 — 사람 자각 vs 자동화 이득).

## 결과

- 쉬워지는 것: 승인·감사·실행의 단일 책임 지점. agent-service 의 읽기 전용 유지. 조치 이력이 `action_approvals` 한 테이블로 남는다.
- 어려워지는 것: 왕복에 토픽 2개(`pending`/`decisions`) — 신규 토픽 1개의 kafka-init 갱신, agent-service 에 컨슈머 1개 추가. 회복 확인 루프는 그래프 재개 쪽 책임으로 남는다.
- 되돌리려면: 실행 로직을 agent-service 도구로 이동하고 decisions 토픽을 제거 — 승인 API·테이블은 재사용 가능.
