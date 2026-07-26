# ADR-0001: 모노레포 채택

- 상태: 승인됨
- 날짜: 2026-07-13

## 맥락

이 플랫폼은 언어와 런타임이 다른 세 서비스(control-plane: Kotlin/Spring Boot, agent-service: Python/LangGraph, target-app: Kotlin/Spring Boot)와 이를 묶는 로컬 인프라(docker-compose, Prometheus, Grafana)로 구성된다.

선행 프로젝트에서는 Spring Boot 서비스(ai-code-reviewer)와 Python 에이전트(ai-agent-service)를 별도 저장소(polyrepo)로 운영했고, 다음 마찰을 겪었다.

- 하나의 기능(예: 분산 trace 전파)이 두 저장소에 걸친 커밋으로 쪼개져 변경 이력 추적이 어려웠다.
- 두 서비스를 함께 기동하는 docker-compose 파일이 어느 저장소에 있어야 할지 애매했다.
- 서비스 간 API 계약 변경 시 양쪽 저장소의 커밋을 수동으로 짝지어야 했다.

이 플랫폼은 시나리오 하나(예: latency 급증 대응)가 control-plane·agent-service·infra 를 동시에 건드리는 구조라, 변경 단위가 저장소 경계를 상시 넘나든다.

## 결정

세 서비스와 인프라 설정, 설계 문서를 하나의 저장소 `ai-ops-platform` 에 담는 모노레포를 채택한다. 단, 루트 통합 빌드 시스템(Bazel, Nx 등)은 도입하지 않는다 — 각 디렉토리는 독립된 빌드 단위(Gradle 프로젝트 2개, uv 프로젝트 1개)로 유지하고, 통합 지점은 `infra/` 의 docker-compose 하나로 한정한다.

## 검토한 대안

| 대안 | 장점 | 단점 | 기각 사유 |
|------|------|------|-----------|
| polyrepo (서비스별 저장소) | 저장소별 권한/CI 분리 용이, 단일 언어 툴링 | 교차 변경 추적 어려움, compose 파일 위치 애매, 계약 변경 시 커밋 짝짓기 수동 | 선행 프로젝트에서 마찰 실증. 이 플랫폼은 교차 변경이 기본 단위 |
| 모노레포 + 통합 빌드 (Bazel/Nx) | 전체 빌드 그래프 일원화, 영향 범위 자동 산출 | 학습·유지 비용 높음, Kotlin+Python 혼합 설정 복잡 | 데모/학습 규모(서비스 3개)에 과함. compose 로 충분 |

## 결과

- 쉬워지는 것: 시나리오 단위 원자적 커밋, `infra/` 단일 진입점 기동, 서비스 간 API 계약 변경의 단일 PR 처리.
- 어려워지는 것: CI 가 다국어 빌드를 커버해야 함 (디렉토리별 트리거 분리 필요), 저장소가 커질수록 clone/검색 비용 증가 — 이 규모에서는 무시 가능.
- 되돌리기: 각 디렉토리가 독립 빌드 단위라 `git filter-repo` 등으로 서비스별 저장소 분리 가능. 통합 빌드 시스템을 도입하지 않은 것이 분리 비용을 낮게 유지한다.

## 추가 사항 (2026-07-26): compose 파일의 기능별 분리 — 진입점은 단일 유지

서비스가 17개(Kafka 도입 시점)로 늘며 단일 docker-compose.yml 의 탐색 부담이 커져, Compose `include` 로 파일을 기능별 분리했다. "통합 지점은 docker-compose 하나"는 **진입점 기준으로 유지**된다 — 실행은 여전히 `infra/` 에서 `docker compose up` 하나이고, 루트 파일이 조각을 병합한다.

- 구성: 루트 `docker-compose.yml` (핵심 앱 + postgres + include 선언) / `compose.monitoring.yml` / `compose.langfuse.yml` / `compose.kafka.yml`
- 조각 배치는 `infra/` 루트 — include 의 상대 경로는 각 조각 파일 위치 기준이라, 하위 디렉토리로 옮기면 볼륨 마운트 경로를 전부 재작성해야 한다
- 제약: YAML 앵커는 파일 경계를 못 넘는다 — 공용 `x-logging` 은 4개 파일에 동일 내용 복제 (변경 시 동기 필요, 각 파일 주석으로 표기)
- 파일 간 `depends_on`(langfuse → postgres)은 include 병합 후 판정이라 문제없음
- 검증: 분리 전후 `docker compose config` diff — 서비스·볼륨·네트워크 동일 (유일한 차이는 실행에 관여하지 않는 최상위 x-확장 필드 표시 여부), 기동 시 전 컨테이너 재생성 없음
- 기각 대안: `-f` 다중 지정 오버레이 — 실행 명령에 플래그가 필요해 단일 진입점 원칙과 충돌
