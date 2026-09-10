# evaluation-service

LLM 품질 지속 검증 — Python. `ops.analysis.results` 를 별도 컨슈머 그룹으로 읽어 샘플링 → 인시던트 시간창 재조회(Prometheus·Loki) → LLM-as-a-Judge(llm-gateway 경유) → `ops.evaluation.results` 발행. 설계는 [`docs/quality-evaluation.md`](../docs/quality-evaluation.md), 결정 배경은 [ADR-0019](../docs/adr/0019-llm-quality-continuous-evaluation.md).

응답 경로(인시던트 처리·보고서 저장) 밖에서 동작한다 — 이 서비스를 내려도 인시던트 처리는 무영향.

## 요구 사항

- [uv](https://docs.astral.sh/uv/) (Python 3.13 은 `.python-version` 기준)
- 설정: `.env.example` 을 `.env` 로 복사 후 `AUTH_CLIENT_SECRET` 기입 (auth-server 의 `evaluation-service` 클라이언트 시크릿, ADR-0016)

## 실행

```bash
uv sync
uv run uvicorn evaluation.main:app --reload --port 8001
```

- 헬스체크: http://localhost:8001/health (샘플링 프로파일·재조회·Kafka·OTLP 활성 여부)
- compose 에서는 `docker compose up evaluation-service` — 컨테이너 주소는 `infra/docker-compose.yml` 이 덮어쓴다

## 테스트

```bash
uv run pytest
```

## 구조

| 경로 | 역할 |
|------|------|
| `evaluation/events/results_consumer.py` | `ops.analysis.results` 소비 → 샘플링 → 재조회 → Judge → 발행. 커밋 규약은 agent-service 컨슈머와 동일 (at-least-once, 인프라 실패만 커밋 보류) |
| `evaluation/sampling.py` | 프로파일 2종(`experiment`/`production`) + 보조 조건(critical Alert·승인 요청) + incident_id 해시 결정론 |
| `evaluation/evidence.py` | 시간창(발화 5분 전 ~ `completed_at`) Prometheus 4질의 + Loki ERROR/WARN 재조회, 시트·Judge 공용 요약 문장 |
| `evaluation/judge.py` | Judge 계약(3차원 점수·실패 유형·`ops.evaluation.results` 페이로드). 구현 전에는 `PendingJudge` 스텁 |
| `evaluation/config/` | 설정·로깅·OTel (agent-service 이식, `service.name=evaluation-service`) |
| `golden/` | 골든셋 — 라벨링 시트(`labeling/*.md`)·정답 정정(`ground-truth-overrides.json`)·`v1.jsonl` (평가 자산, git 추적) |
| `scripts/` | 시트 생성(`make_labeling_sheets.py`)·Judge 예비 실측(`judge_preview.py`)·골든셋 조립(`build_golden.py`) |

## 어휘

소비 스팬(`ops.analysis.results process`)에 `aiops.evaluation.sampled` · `sampled_reason` · `sample_rate` · `sample_profile` · `evidence` 를 남긴다. 표준(`gen_ai.evaluation.*`)은 Judge 판정에만 쓴다 — 경계는 설계 문서 §6.
