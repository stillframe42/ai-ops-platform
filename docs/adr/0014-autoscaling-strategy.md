# ADR-0014: 에이전트 워크로드 오토스케일링 전략

- 상태: 승인됨
- 날짜: 2026-08-14

## 맥락

K8s 이전(ADR-0013) 후 컴포넌트별 스케일링 전략이 필요하다. 핵심 질문은 "LLM 에이전트
워크로드의 수요 신호는 무엇인가" — 그래프 실행의 병목은 CPU 연산이 아니라 **LLM API 응답
대기(I/O)** 라서, 표준 HPA 의 CPU 사용률은 수요와 비례하지 않는다 (인시던트가 쌓여도
CPU 는 유휴에 가깝다). 반면 처리 대기 수요는 Kafka 컨슈머 lag(ops.incidents)에 그대로
나타난다. 제약: ops.incidents 는 3파티션 (DAY 17 설계 — 초과 replica 는 유휴),
agent-service 는 ops.actions.decisions 컨슈머도 겸해 최소 1 pod 상주가 승인 재개의 전제.

## 결정

**agent-service 는 KEDA Kafka lag 기반으로 스케일한다** — ScaledObject: ops.incidents
lagThreshold 5, min 1 (scale-to-zero 불가 — decisions 컨슈머 상주), **max 3 = 파티션 수**
(상한이지 목표 아님 — 실제 replica 는 HPA 산식 ceil(lag/threshold)). 차트 배선은
agent-service 의 `keda.enabled` 게이트 (기본 false — KEDA CRD 없는 클러스터에서도 차트
유효, KEDA 자체는 플랫폼 애드온으로 별도 설치). **control-plane 은 min 1 고정, HPA 생략**
— 다중 replica 선행 검토 결과(아래) 스케일 실익이 없고 경합 소음만 추가된다.

control-plane 검토 상세: ① @Scheduled 승인 스윕 — 분산 락 없이 replica 수만큼 중복 실행
(reminded_at 선기록·decide 멱등이 방어하나 중복 재알림 경합 창 존재) ② Socket Mode —
Slack 이 다중 연결에 이벤트를 분배해 기능은 유지되나 수신 주체 비결정 ③ actions.pending
1파티션 — 두 번째 replica 의 컨슈머는 유휴 ④ 수신 부하(웹훅·MCP·승인 API) 자체가 저부하.

## 검토한 대안

| 대안 | 장점 | 단점 | 기각 사유 |
|------|------|------|-----------|
| CPU 기반 HPA (agent-service) | 표준 수단, 추가 구성요소 없음 (metrics-server 만) | I/O 대기 워크로드라 CPU 가 수요를 반영하지 못함 — 인시던트 폭주에도 스케일 신호 부재 | 신호 자체가 틀림 — "LLM 워크로드는 왜 CPU 스케일링이 안 맞는가"의 본질 (CPU 실측 병기는 metrics-server 미설치로 생략 — 순연 강등 ⑪, 구조 논거로 충분 판단) |
| scale-to-zero (KEDA min 0) | 유휴 비용 0 | decisions 컨슈머 부재 — 승인 재개가 다음 인시던트까지 정지 | 승인 왕복(ADR-0005)의 전제 훼손 |
| control-plane 도 HPA | 대칭 구성 | 스윕 중복·수신 비결정 등 다중 replica 전제 미비 (위 검토) | 실익 없는 위험 — 다중화는 스윕 외부화(CronJob 등)·리더 선출 도입 시 재검토 |

## 실측 (2026-08-14, 합성 인시던트 10건 동시 주입)

- 12:48:29 웹훅 10건 주입 → 12:49:32 lag 9 관측 → **12:49:55 스케일아웃 1→2** (감지 후
  23초, ceil(9/5)=2 — 산식 그대로) → lag 계단식 하강 9→6→3→0 (**배치 커밋 단위 3** —
  getmany 배치 전체 성공 시 커밋의 가시화) → 12:51:58 lag 0 → 보고서 10건 전부 completed
  (9건 조치 불요 종결 + 1건 승인 경유) → **12:59:57 스케일인 2→1** (HPA 안정화 창 경과).
  **무유실 10/10**.
- 스케일 리밸런싱 보호: 1→2 합류 시점(12:49:48) 파티션 회수가 처리 중 배치와 겹침 —
  배치 커밋 + seek_to_committed 재전달 + 멱등 2층(ADR-0011)이 흡수, 유실·중복 종결 0.
  Durable Execution(ADR-0009)과의 조합이 "스케일 중에도 안전"의 근거 (Phase 4 pod 삭제
  실험과 같은 원리).
- lag 신호의 특성 (운영 메모): interrupt(승인 대기) 도달도 커밋이므로 lag 는 "분석 대기"만
  반영 — 승인 대기 인시던트는 스케일 수요로 잡히지 않는다 (의도된 동작: 대기는 컨슈머
  자원을 점유하지 않음). max 3 시연은 lag ≥ 11 필요 (이번 주입 10건 = 2 replica 가 정답).

## 결과

- 쉬워지는 것: 인시던트 폭주 시 자동 병렬화 (pod 당 Semaphore 3 × max 3 = 동시 그래프
  9 상한 — LLM 비용 상한의 근거 ⑦), 유휴 시 1 pod 로 수렴. 스케일 판단이 선언(ScaledObject)
  으로 문서화.
- 어려워지는 것: KEDA 설치가 재현 절차에 1단계 추가 (umbrella 밖 플랫폼 애드온 — CRD
  선행 제약). lagThreshold·cooldown 튜닝 책임 발생 (현재 데모 반응성 값 15s/120s).
- 되돌리려면: keda.enabled=false 로 ScaledObject 제거 + KEDA uninstall — replica 는 values
  고정값으로 복귀.
