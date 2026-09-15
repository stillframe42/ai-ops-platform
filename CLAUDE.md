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
| `evaluation-service/` | 보고서 품질 평가 (샘플링 소비·시간창 재조회·LLM-as-a-Judge), 골든셋 자산 | Python (uv) |
| `target-app/` | 모니터링 대상 데모 앱 (fault-injection 제공) | Spring Boot |
| `infra/` | docker-compose, Prometheus, Grafana | - |
| `docs/` | 시나리오·C4 아키텍처·ADR | Markdown + Mermaid |

## 버전 관리

- [Conventional Commits](https://www.conventionalcommits.org/) + 한국어 제목 (예: `docs: 시스템 시나리오 정의 추가`)
- 모노레포 범위(scope)는 디렉토리명 사용 (예: `feat(control-plane): ...`, `chore(infra): ...`)
- 커밋 author 는 `git config user.name`/`user.email` 사용, `Co-Authored-By` 추가 금지
- 커밋은 사용자가 명시적으로 요청할 때만. push 는 항상 사용자가 직접
- `plans/` 는 로컬 전용 작업 로그 — git 추적 금지 (.gitignore 처리됨), 산출물도 커밋하지 않는다

## 코드 작성 관례

- Kotlin/Spring 코드 작성 규칙은 `.claude/guides/code-conventions.md` 를 따른다 (스타일·주석·파일 단위 분리 등)

## 시점 표기 규칙 (주석·문서 공통)

- git 추적 대상(코드 주석·설정·README·`docs/` 등)에는 **"Phase N"·"N주차"·"N월" 표기를 쓰지 않는다.** 시간이 지나면 어느 시점인지 식별할 수 없고, 월마다 반복되기 때문이다.
- 허용: **완전한 날짜**(`2026-08-22`, `8/22`), **DAY N**(실작업일 연번), **ADR 참조**. 시점 근거가 필요하면 이 셋 중 하나만 쓴다.
- 상대 시점은 사건 기반으로 서술한다 ("보안 작업 이후", "게이트웨이 구축 시" 등) — "N주차/N월 이후" 대신.
- 예외: `plans/` (로컬 전용·git 미추적) 내부는 Phase·주차 구조 자체가 작업 관리 축이라 허용. `README.md` 로드맵의 월 섹션 헤더(계획 단위)는 유지.
- 산출물 전달 전 `grep -rn "Phase\|주차\|[0-9]월" <변경 파일>` 로 기계 점검한다.

## 설계 문서 관례

- 아키텍처 결정은 `docs/adr/` 에 ADR 로 기록 (`template.md` 형식)
- 다이어그램은 Mermaid (GitHub 프리뷰 렌더링 기준)

## 작업 체크리스트

날짜별 작업 로그는 `plans/` (로컬 전용, git 미추적) 에서 관리한다. 주 단위 마스터 plan(`weekly-plan.md`)에서 매일 착수 시점에 당일 파일(`tasks_YYYYMMDD.md`)을 분리 생성해 진행을 체크한다. 현재 체크리스트: `plans/202609-2w/tasks_20260915.md`
