# ADR-0011: 에이전트 트리거 — REST 폴링/수동 vs Kafka 이벤트

- 상태: 승인됨
- 날짜: 2026-07-26

## 맥락

3주차 이전의 인시던트 트리거는 수동이었다: 사람이 `POST /incidents/trigger` 를 호출해야 Supervisor 그래프가 실행된다. Alertmanager 는 Alert 발화를 알고 있지만 (2주차 이월: receiver 가 placeholder) 에이전트까지 전달되는 경로가 없어, "chaos 주입부터 보고까지 사람 개입 없음"이라는 3주차 목표의 마지막 고리가 비어 있었다.

전달 경로에 필요한 성질: ① agent-service 다운 중 발생한 Alert 의 무유실 (그래프 실행은 수 분 단위라 다운 창이 실제로 있다), ② Alert 폭풍 시 흡수 (동시 그래프 실행 = LLM 비용·자원이라 무제한 병렬 불가), ③ 결과의 다중 소비 (DAY 20 control-plane 저장·Slack, 4주차 조치 흐름), ④ 인시던트 단위 순서.

## 결정

Alertmanager → control-plane webhook → **Kafka** (`ops.incidents`) → agent-service 컨슈머(aiokafka)로 전환한다. 수동 트리거 API 는 디버그용으로 그대로 유지한다.

- 컨슈머: `group_id=agent-service`, `enable_auto_commit=False`, `auto_offset_reset=earliest` — **처리 완료 후 커밋** (유실보다 중복을 택하고, 중복은 처리 측 멱등이 흡수)
- 커밋 단위: `getmany` 배치 → 동시 처리 (Semaphore 3 — LLM 비용 상한) → 배치 전체 완료 후 커밋. 결과 발행 실패 등 인프라 실패 시 커밋 보류 + `seek_to_committed` 재수신 (미커밋이어도 같은 세션에선 재전달되지 않는 aiokafka 특성 대응)
- 처리 측 멱등 (재전달 흡수 2층): 완주 인시던트는 `is_run_complete` 판정으로 재실행 생략 + **결과만 재발행**, 미완 체크포인트는 Durable Execution `resume` — 오프셋 층(미수신)과 체크포인터 층(처리 중 다운)의 결합
- 결과 발행: 그래프 종료 시 `ops.analysis.results` (key=incident_id) — 실패 종료도 errors 포함 부분 보고서로 발행 (DAY 13 정합)

## 검토한 대안

| 대안 | 장점 | 단점 | 기각 사유 |
|------|------|------|-----------|
| 수동 트리거 유지 | 구현 0 | 자동화 목표 미달성 | 3주차 목표 자체가 자동 흐름 |
| Alertmanager → agent-service 직접 webhook | 중개 없음, 구성 단순 | 다운 중 Alert 유실 (반복 발화만으로는 firing 지속 조건부), 폭주 시 백프레셔 없음, 결과 다중 소비 별도 구현 | 필요 성질 ①②③ 모두 미충족 — 5월 프로젝트의 REST 폴링도 같은 한계 + 폴링 지연 |
| REST 폴링 (agent 가 Alertmanager API 주기 조회) | 수신자 주도라 다운 복구 자연 | 폴링 간격만큼 지연, Alertmanager 가 큐가 아니라 이력 조회 의미론, 결과 경로는 여전히 별도 | 유입은 되지만 ③④ 미해결 — 이벤트 로그가 제공하는 성질을 폴링으로 재구현하게 됨 |
| Kafka (채택) | 소비자 부재 허용(오프셋), 배치·Semaphore 백프레셔, 그룹별 독립 소비, key 순서 | 브로커 = 새 운영 대상 (단일 브로커는 새 단일 장애점), 오프셋·멱등 학습 비용 | — |

## 결과

- 쉬워지는 것: chaos → Slack 전 과정 자동 (사람 개입 없음), agent-service 재기동 시 밀린 인시던트 자동 처리 (Phase 7 검증 예정), DAY 20 결과 컨슈머를 발행 측 무변경으로 추가, 재처리(오프셋 되감기) 가능
- 어려워지는 것: 브로커 운영 (KRaft 단일 — 고가용성은 8월 k8s 소재), at-least-once 중복을 전제한 멱등 설계가 발행(fingerprint 병합)·소비(done 검사) 양층에 필요
- 알려진 유실 창 (실측 기반, `notes/20260726` 계열): control-plane 발행 실패 시 registry 선등록이 반복 발화 재발행을 병합으로 차단 — "Kafka 2분+ 다운 × firing 이 그 안에 해소" 교집합에서 영구 유실. 해소안(발행 실패 콜백 활성 해제 vs DAY 20 수신 영속화)은 Phase 7 실측 후 결정
- 되돌리기: 트리거 경계가 컨슈머 모듈 1곳(`app/events/`)과 webhook 1곳이라, REST 직결로 회귀하려면 webhook 이 agent-service 를 직접 호출하게 바꾸면 된다 — 그래프 실행 계약(`GraphRuntime`)은 트리거 방식과 무관

## 추가 사항 (2026-07-28 — Phase 7 장애 주입 실측)

- **무유실 2층 검증 완료**: ① 오프셋 층 — agent-service 6.5분 다운 중 발행분이 lag 으로 보존, 재기동 +52초에 Slack 도착 ② 체크포인터 층 — 그래프 실행 중 강제 재시작 시 미커밋 재수신 + 미완 체크포인트 resume (Langfuse 트레이스 2분절 실측: 11.7초 절단 → 재개 34.3초 완주, 다운 비용 ~14초)
- **결함 발견·수정**: 브로커 부재 + 메타데이터 만료 시 `KafkaTemplate.send()` 가 비동기 실패가 아니라 **동기 예외**를 던진다 (`max.block.ms` 초과). `whenComplete` 만으로는 못 잡아 webhook 처리 전체가 중단돼 인시던트화 자체가 유실됐다 — 위 유실 창보다 앞단의 더 넓은 경로. 동기 캐치로 수정 (`KafkaEventPublisher`, 테스트 고정)
- **유실 창 재현 확정 (수정판 기준)**: kafka 다운 중 발화 → 발행 실패 로그 + 선등록 → firing 이 다운 창 안에 해소 → 재기동 후 끝 오프셋 무변화 — 인시던트가 로그에만 존재. 프로듀서 버퍼 재시도 회복은 "마지막 발행 후 metadata.max.age(5분) 이내의 짧은 다운"에만 성립
- **부재 vs 다운 구분**: 기동 시 브로커 부재 = 컨슈머 생성 실패로 Exit 1 / 실행 중 부재 = rebootstrap 재시도로 생존
- 해소안 결정은 보류 상태 유지 — 후보: ① 발행 실패 시 활성 해제(다음 재발화가 재시도) ② registry 영속화(4주차 승인 테이블과 결합) ③ 수용+문서화

## 추가 사항 (2026-07-31 — 유실 창 해소안 결정)

- **① 발행 실패 시 활성 해제 채택·구현**: `EventPublisher.publish` 가 동기 접수 성패를 반환하고, 실패 시 `IncidentRegistry.untrack`(인시던트 id 일치 조건부 제거 — 경합 보호)으로 활성 해제한다. 다음 발화(repeat_interval 재전송 포함)가 재시도 주체가 되어 유실 창이 닫힌다. 반환값이 못 잡는 비동기 실패(delivery timeout)는 알려진 한계로 계약에 명시 — Future 전파는 수집 경로의 비동기 합성 비용 대비 보류
- **② registry 영속화는 채택하지 않음**: 승인 테이블(action_approvals)과 도메인이 다르다 (alert 병합 상태 vs 조치 승인) — 결합 이득이 없고, ① 이 유실 창을 닫은 뒤 남는 재기동 리스크는 "중복 인시던트 발행" 1건으로 컨슈머 thread_id 차단 + upsert 멱등의 기존 2차 방어와 동일 (registry KDoc 의 수용 근거 유지)

## 참고

- "서버 다운이 새 실패 지점" 단점은 Kafka 고유가 아니라 중개 계층 도입 공통 비용 (ADR-0010 보정과 같은 구조)
- 토픽 명세 (파티션·보존·key)는 DAY 17 결정 — raw(1, groupKey) / incidents·analysis.results(3, incident_id) / actions.pending(1, 자리만), 보존 7일, auto-create 비활성
