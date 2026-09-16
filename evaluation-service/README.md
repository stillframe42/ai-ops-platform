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

- 헬스체크: http://localhost:8001/health (샘플링 프로파일·재조회·Kafka·OTLP 활성 여부·Judge 프롬프트 버전)
- compose 에서는 `docker compose up evaluation-service` — 컨테이너 주소는 `infra/docker-compose.yml` 이 덮어쓴다

## 테스트

```bash
uv run pytest                                         # 단위 (스택·LLM 무의존)
uv run pytest -m golden tests/test_golden_regression.py -rs   # 골든셋 회귀 — 실 Judge 20건, baseline 대비 MAE 악화 0.05 초과·false pass 증가 시 실패
```

골든셋 회귀는 기본 제외(`pyproject.toml` addopts)다 — 게이트웨이·auth-server 가 닿는 곳에서 `LLM_BASE_URL`·`AUTH_TOKEN_URL`·`AUTH_CLIENT_SECRET` 을 주고 돌린다 (compose 호스트: `http://localhost:8090/v1`·`http://localhost:8091/oauth2/token`, 시크릿은 `infra/.env` 의 `AUTH_CLIENT_SECRET_EVALUATION_SERVICE`). 주 1회 GitHub Actions(`golden-regression.yml`)는 같은 값을 리포지토리 시크릿으로 받는다. baseline 갱신(프롬프트 버전 변경 시):

```bash
uv run python scripts/judge_baseline.py --repeat 3 --out golden/judge-baseline.json
```

## 구조

| 경로 | 역할 |
|------|------|
| `evaluation/events/results_consumer.py` | `ops.analysis.results` 소비 → 샘플링 → 재조회 → Judge → 발행. 커밋 규약은 agent-service 컨슈머와 동일 (at-least-once, 인프라 실패만 커밋 보류) |
| `evaluation/sampling.py` | 프로파일 2종(`experiment`/`production`) + 보조 조건(critical Alert·승인 요청) + incident_id 해시 결정론 |
| `evaluation/evidence.py` | 시간창(발화 5분 전 ~ `completed_at`) Prometheus 4질의 + Loki ERROR/WARN 재조회, 시트·Judge 공용 요약 문장 |
| `evaluation/judge.py` | Judge 계약(3차원 점수·실패 유형·`ops.evaluation.results` 페이로드) + 응답 정규화(앵커 스냅·실패 유형 규칙) |
| `evaluation/judge_gateway.py` | 게이트웨이 Judge — `X-Task-Type: evaluation-judge`·`X-Cache-Control: no-cache`·OAuth·`wrap_untrusted`, 실패는 `error.type` 으로 남기고 판정 없음 |
| `evaluation/judge_prompt.py` · `evaluation/prompts/judge/<version>.md` | 버전 파일 시스템 프롬프트(설정 `EVAL_JUDGE_PROMPT_VERSION`) + 보고서·재조회 → user 메시지 조립 |
| `evaluation/config/otel_evaluation.py` | 평가 텔레메트리 — `gen_ai.evaluation.result` 이벤트(로그 레코드 + 스팬 이벤트)·`aiops.evaluation.score` 히스토그램·카운터 3종(판정·Judge 호출·샘플링) |
| `evaluation/config/` | 설정·로깅·OTel 3시그널(trace·metric·log — agent-service 이식, `service.name=evaluation-service`) |
| `golden/` | 골든셋 — 라벨링 시트(`labeling/*.md`)·정답 정정(`ground-truth-overrides.json`)·`v1.jsonl`·Judge baseline 스냅샷(`judge-baseline.json`) (평가 자산, git 추적) |
| `scripts/` | 시트 생성(`make_labeling_sheets.py`)·골든셋 조립(`build_golden.py`)·Judge 일관성 측정(`judge_baseline.py`)·리뷰 승격(`promote_golden.py` — control-plane 리뷰 큐 `status=promoted` → 라벨링 시트 + 보고서·재조회 파일 생성, 이어서 `build_golden.py` 재생성)·A/B 실험 리포트(`experiment_report.py` — control-plane 실험 평가 목록 + Tempo `invoke_agent analysis` 스팬(지속 시간·자식 chat 스팬 토큰 × gateway.yml 단가) → variant 별 표·부트스트랩 95% CI(B=2000, seed 고정)·사전 기준 판정(F +0.05 && 비용 +30% 이내, CI 가 0 포함이면 보류) 마크다운). 원본은 시트 하나, `v1.jsonl` 은 매번 시트 전체에서 재생성 |
| `tests/test_golden_regression.py` | `@pytest.mark.golden` — baseline 대비 회귀 (기본 제외) |

## 어휘

소비 스팬(`ops.analysis.results process`)에 `aiops.evaluation.sampled` · `sampled_reason` · `sample_rate` · `sample_profile` · `evidence` 를 남긴다. Judge 판정은 평가 스팬(`evaluate incident-report`, 원 실행 `invoke_workflow` 로 link) 아래 `chat default` CLIENT 스팬 + 차원별 `gen_ai.evaluation.result` 이벤트 + `aiops.evaluation.score` 히스토그램(버킷 = 앵커 경계)으로 남는다 — 경계는 설계 문서 §6, 스팬 트리는 `docs/otel-genai-mapping.md` §4.

`ops.evaluation.results` 페이로드(`Evaluation.to_payload`): `incident_id` · `scores{faithfulness,actionability,severity_accuracy}{score,reason}` · `failure_mode` · `low_quality` · `judge_model` · `prompt_version`(Judge) · `analysis_prompt_version`(평가 대상 분석 프롬프트) · `experiment_name` · `experiment_variant`(보고서 `experiment.{name,variant}` — A/B 실험 축, ADR-0019 결정 ③, 실험 밖이면 null) · `evidence_available` · `evaluated_at`.

메트릭 속성(`aiops.evaluation.score` 히스토그램·`aiops.evaluation.verdicts` 카운터 공통): `aiops.evaluation.severity` · `aiops.prompt.version` · `aiops.evaluation.judge_prompt_version` · `aiops.experiment.name` · `aiops.experiment.variant` — 실험 축 둘은 실험 밖 보고서에서 `none` 으로 채운다 (라벨 유무가 갈리면 Prometheus `sum by` 시리즈가 두 갈래로 나뉜다). 히스토그램은 `aiops.evaluation.dimension`, 판정 카운터는 `aiops.evaluation.failure_mode` · `low_quality` 를 더 가진다. 같은 실험 축은 평가 스팬 속성으로도 남되 실험 밖이면 속성 자체를 두지 않는다. `gen_ai.evaluation.result` 이벤트는 표준 어휘 그대로 — 확장 경계는 `docs/otel-genai-mapping.md` §5.
