# ADR-0009: LangGraph 체크포인터 — 처음부터 PostgreSQL

- 상태: 승인됨
- 날짜: 2026-07-20

## 맥락

인시던트 처리 그래프는 실행에 수 분이 걸린다 (LLM·도구 호출이 노드당 수십 초). 실행 중 agent-service 가 죽으면 — 배포, OOM, 강제 종료 — 처리 중이던 인시던트가 통째로 사라지고, 재실행하면 완료됐던 노드의 LLM 호출 비용을 다시 치른다. LangGraph 의 Durable Execution 은 super-step 마다 상태를 체크포인터에 저장해 "동일 thread_id + 입력 None" invoke 로 중단 지점부터 재개할 수 있게 한다. 체크포인터 구현 선택이 이 결정의 대상이다. 제약 조건: thread_id = 인시던트 ID (수동 트리거 `POST /incidents/trigger` 가 발급), 인프라는 docker-compose 단일 통합점 (ADR-0001), PostgreSQL 은 "용도 정의 없이 활성화 금지" 원칙으로 compose 에 주석 예약돼 있었다.

## 결정

**개발 첫날부터 PostgreSQL 체크포인터(`AsyncPostgresSaver`)를 쓴다.** compose 의 postgres 를 "용도: LangGraph checkpoint 저장소"로 명기해 활성화하고, FastAPI lifespan 에서 연결을 열어 `setup()`(멱등) 후 `build_graph(checkpointer=...)` 로 조립한다. 인메모리(`InMemorySaver`)는 단위 테스트 전용으로 한정한다 — 체크포인터 인터페이스가 동일하므로 재개·상태 조회 계약은 InMemorySaver 로 검증하고, 프로세스 경계를 넘는 지속성만 실측(컨테이너 강제 종료 → 재개)으로 확인한다.

## 검토한 대안

| 대안 | 장점 | 단점 | 기각 사유 |
|------|------|------|-----------|
| InMemorySaver 로 시작, 나중에 PostgreSQL 전환 | 인프라 의존 없음, 즉시 동작 | 체크포인트가 프로세스 메모리에 있어 **재기동하면 소실 — "프로세스 죽음에서 살아남기"라는 도입 목적 자체가 성립 안 함** | 이 플랫폼에서 체크포인터의 존재 이유가 곧 장애 복원력 — 목적을 충족 못 하는 중간 단계를 거칠 이유가 없다. 전환 비용도 착수 시점이 가장 싸다 (스키마·운영 관례가 없는 지금) |
| SQLite 체크포인터 (파일 지속성) | 별도 컨테이너 불필요, 재기동 생존 | 단일 파일 잠금 — 동시 인시던트 처리와 상성 나쁨, async 지원 제한적 | 컨테이너화된 스택에서 파일 볼륨 관리가 PostgreSQL 컨테이너보다 오히려 번잡하고, 3주차(Kafka 소비, 동시 인시던트) 확장에서 어차피 교체 대상 |

## 결과

- 쉬워지는 것: 실행 중 강제 종료 → `POST /incidents/{id}/resume` 재개가 성립 (완료 노드의 LLM 비용 재지불 없음). 체크포인트 히스토리가 DB 에 남아 상태 조회 API(`GET /incidents/{id}/state`, `/history`)와 이후 감사·디버깅의 기반이 된다. 단위 테스트는 InMemorySaver 로 DB 없이 빠르게 유지.
- 어려워지는 것: 스택에 상태 있는 컨테이너가 추가된다 — 볼륨 관리(postgres:18 은 `/var/lib/postgresql` 마운트), 기동 순서 의존(healthcheck + depends_on). 로컬 개발(uv run)도 CHECKPOINT_DB_URL 이 필요해진다.
- 되돌리기: 체크포인터는 `build_graph(checkpointer=...)` 주입 지점 하나로 격리돼 있어 구현 교체는 lifespan 의 open_runtime 수정으로 끝난다 — 결정을 되돌려도 그래프·API 코드는 무변경.
