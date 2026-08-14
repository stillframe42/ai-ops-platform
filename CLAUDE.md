# AI Ops Platform - 프로젝트 가이드

## 프로젝트 개요

멀티 에이전트 기반 AIOps 플랫폼. 데모 앱(target-app)의 장애를 감지·분석하고, human-in-the-loop 승인을 거쳐 대응 조치를 수행한다.

기존 ai-code-reviewer / ai-agent-service 와 코드 의존 없는 별개 프로젝트.

## 모노레포 구조

각 디렉토리는 독립 빌드 단위. 통합 지점은 `infra/` 의 docker-compose 하나 (근거: `docs/adr/0001-monorepo.md`).

| 디렉토리 | 역할 | 스택 |
|----------|------|------|
| `control-plane/` | 관제/API/게이트웨이, human-in-the-loop 승인 | Spring Boot 4.x + Kotlin |
| `agent-service/` | 모니터링/분석/실행 멀티 에이전트 | Python + LangGraph (uv) |
| `target-app/` | 모니터링 대상 데모 앱 (fault-injection 제공) | Spring Boot |
| `infra/` | docker-compose, Prometheus, Grafana | - |
| `docs/` | 시나리오·C4 아키텍처·ADR | Markdown + Mermaid |

## 버전 관리

- [Conventional Commits](https://www.conventionalcommits.org/) + 한국어 제목 (예: `docs: 시스템 시나리오 정의 추가`)
- 모노레포 범위(scope)는 디렉토리명 사용 (예: `feat(control-plane): ...`, `chore(infra): ...`)
- 커밋 author 는 `git config user.name`/`user.email` 사용, `Co-Authored-By` 추가 금지
- 커밋은 사용자가 명시적으로 요청할 때만. push 는 항상 사용자가 직접
- `plans/` 는 로컬 전용 작업 로그 — git 추적 금지 (.gitignore 처리됨), 산출물도 커밋하지 않는다

## 설계 문서 관례

- 아키텍처 결정은 `docs/adr/` 에 ADR 로 기록 (`template.md` 형식)
- 다이어그램은 Mermaid (GitHub 프리뷰 렌더링 기준)

## 작업 체크리스트

날짜별 작업 로그는 `plans/` (로컬 전용, git 미추적) 에서 관리한다. 주 단위 마스터 plan(`weekly-plan.md`)에서 매일 착수 시점에 당일 파일(`tasks_YYYYMMDD.md`)을 분리 생성해 진행을 체크한다. 현재 체크리스트: `plans/202608-2w/tasks_20260813.md`
