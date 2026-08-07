# ADR 인덱스

아키텍처 결정 기록 — 형식은 [template.md](template.md), 기각·보류 결정도 기록 대상이다. 결정의 발단이 된 미결 항목 목록은 [scenarios.md 의 ADR 후보 표](../scenarios.md#미결-사항--adr-후보) 참고.

| 번호 | 제목 | 결정 요지 | 날짜 |
|------|------|-----------|------|
| [0001](0001-monorepo.md) | 모노레포 구조 | 디렉토리별 독립 빌드, 통합 지점은 infra/ docker-compose 하나 | 2026-07-13 |
| [0002](0002-observability-access-path.md) | 에이전트의 관측 데이터 접근 경로 | Prometheus/Loki 직접 조회 (읽기는 직접, 조치 경로는 0005 로 분리) | 2026-07-16 |
| [0003](0003-alertmanager-webhook.md) | 감지 트리거 방식 | Alertmanager 룰 기반 웹훅 — 임계값형 1차 판정은 Alert Rule, 추세형만 에이전트 판정 | 2026-07-14 |
| [0004](0004-loki-adoption.md) | 로그 스택 도입 | Loki 도입 (범위 제한), 히스토리는 Prometheus 기본 보존 | 2026-07-14 |
| [0005](0005-action-executor.md) | 조치 실행 주체 | control-plane 대행 — LLM 프로세스에 인프라 변경 권한 미부여, 승인·감사·실행 수렴. RESTART_APP 은 자동 실행 제외 (추가 사항) | 2026-07-31 |
| [0006](0006-slack-approval-ux.md) | Slack 승인 UX | Slack App + Socket Mode, Block Kit 승인 버튼, 승인자 user ID 감사, 30m 재알림 → 60m 만료 | 2026-07-31 |
| [0007](0007-llm-provider.md) | LLM 프로바이더 | Anthropic Claude Sonnet 5 기본, 설정(`프로바이더:모델`)으로 전환 가능 | 2026-07-16 |
| [0008](0008-hybrid-routing.md) | Supervisor 라우팅 | 하이브리드 — 명확한 전이는 규칙, 모호 구간만 LLM | 2026-07-19 |
| [0009](0009-postgres-checkpointer.md) | LangGraph 체크포인터 | 처음부터 PostgreSQL (Durable Execution), thread_id = incident id | 2026-07-20 |
| [0010](0010-mcp-tool-exposure.md) | 운영 도구 노출 방식 | MCP 표준 (Streamable HTTP) — REST 직접 호출 대체 | 2026-07-23 |
| [0011](0011-kafka-trigger.md) | 에이전트 트리거 | Kafka 이벤트 (`ops.incidents`) — 수동 커밋 + 멱등 2층, 다운 중 무유실 실측 | 2026-07-26 |
