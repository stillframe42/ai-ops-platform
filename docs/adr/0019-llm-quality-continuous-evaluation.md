# ADR-0019: LLM 품질의 지속 검증 체계 — 골든셋 / 온라인 Judge / 실험 3층

- 상태: 승인됨 (2026-09-16 확정 — 3층 구현·골든셋 baseline·실험 리포트 1건 뒤. 초안 2026-09-09)
- 날짜: 2026-09-09 (확정 2026-09-16)

## 맥락

분석 에이전트의 보고서 품질을 판단할 자산이 이 저장소에 하나도 없다 (2026-09-09 실측: `judge`·`golden`·`faithful`·`rubric` 전 저장소 0건). 프롬프트 4종은 모듈 상수라 버전 개념이 없고, 프롬프트나 모델을 바꿔도 좋아졌는지 확인할 방법은 Slack 보고서를 사람이 읽는 것뿐이었다. 보고서의 `confidence` 는 에이전트의 자기 평가라 품질 측정이 아니다 (`notes/qna` 2026-08-03 정리 — "confidence = 근거 가용성").

DAY 42~44 (2026-09-03~09-08) 에 관측 축이 완성됐다 ([ADR-0018](0018-observability-vendor-neutral.md)): 인시던트 1건이 하나의 traceId 로 조회되고, 비용은 `gateway_cost_usd_total{service}` 와 `gen_ai.usage.*` 로, 성공률·스텝 수는 Agent Spans 에서 파생된다. 남은 축은 품질이다 (README 로드맵 "AI 전용 메트릭" 행).

제약: 실 사용자 트래픽이 없어(비목표) "운영 트래픽"은 chaos 재주입과 합성 발화다. 하루 인시던트가 한 자리라 샘플링 비율은 데모와 상시 운영을 분리해야 한다. OTel GenAI 컨벤션의 평가 어휘(`gen_ai.evaluation.result` 이벤트)는 Development 상태이고 공식 평가기 패키지는 없다. Langfuse 는 OTLP 로 점수를 받지 않는다. 게이트웨이는 incident_id 를 모르고 요청 헤더 `X-Task-Type`·`X-Cache-Control` 과 JWT client_id 만 안다.

## 결정

**품질 검증을 세 층으로 세운다.** ① 오프라인 골든셋 — 사람이 라벨한 보고서 20건(git 추적 자산)으로 Judge 자체를 검증한다(MAE·순위 상관 baseline, 주 1회 회귀). ② 온라인 Judge — 별도 서비스 `evaluation-service` 가 `ops.analysis.results` 를 층화·결정론 샘플링으로 소비해 인시던트 시간창을 재조회한 근거와 함께 다른 모델의 LLM 에 3차원(Faithfulness·Actionability·Severity 정확도)을 채점시키고, 결과를 `ops.evaluation.results` 로 control-plane 에 저장하며 `gen_ai.evaluation.result` 표준 이벤트 + 자체 메트릭 `aiops.evaluation.score` 로 기록한다. ③ 실험 — 배정은 agent-service(incident_id 해시, 프롬프트 variant 선택), 모델 오버라이드만 게이트웨이(`X-Experiment-Variant` 헤더, `gateway.yml` 에 정의된 variant 만 허용)의 2단 구조로, 승자 기준은 실험 전에 정한다. 세부 정의·앵커·샘플링 표·어휘 경계는 [`docs/quality-evaluation.md`](../quality-evaluation.md) 가 단일 원천이다.

선행 결정 (2026-09-09 실측 근거):

| 항목 | 결정 | 근거·실측 |
|---|---|---|
| 평가 입력 원천 | 인시던트 시간창(발화 5분 전 ~ `completed_at`)으로 Prometheus·Loki 재조회 | 페이로드에 도구 원본 없음. 2026-09-02 케이스 재조회로 5xx 비율 0.497 + ERROR 50건 확인, 합성 발화 케이스는 5xx 0 — 판정 재료로 충분. 앱 무변경 |
| 서비스 실체 | 별도 디렉토리 `evaluation-service/` (Python) | 공통 모듈 6종 ≈ 400줄 복사(ADR-0001 독립 빌드). JWT client_id 가 곧 비용 라벨이라 Judge 비용 분리가 코드 0. 응답 경로·KEDA 스케일과 격리 |
| 결과 저장소 | 토픽 `ops.evaluation.results` → control-plane Flyway V5 `incident_evaluations` (+ `review_status` 컬럼) | 리뷰 큐·승격·Slack 태그가 human-in-the-loop = control-plane 역할. 기존 `AnalysisResultConsumer`→`Service`→JPA 패턴 그대로 |
| Judge 모델 | **gpt-5.6-terra** (교차 프로바이더, 잠정) | 자기 선호 편향 회피. 예비 5건 대조(2026-09-09): MAE 는 haiku 우위(0.29 vs 0.35)였으나 심각도 과대 3건을 gpt 는 전부, haiku 는 1건만 포착 — 관문 용도에서는 통과시키는 오류가 더 무겁다. 3회 반복 S 판정 불변. 확정 조건 = 루브릭 정정 후 20건 재측정에서 false pass 0. `temperature` 미지정 |
| 실험 배정 | agent-service 배정 + 게이트웨이 헤더 오버라이드 (2단) | 게이트웨이는 incident_id·프롬프트를 모른다. 비용 라벨은 `Route`→`GatewayMetrics`→`CostEntry` 3곳 동반 변경 |
| 샘플링 | 프로파일 2종 (`experiment` 100% / `production` P1 100·P2 30·P3 10·기본 15) + critical·approval 보조 100% + 결정론 해시 | 층화 키(판정 severity)가 평가 대상이라 보조 조건 필요. 데모 트래픽에서 15% 는 0~1건 |
| 표준 어휘 | `gen_ai.evaluation.*` 4속성 + `gen_ai.evaluation.result` 이벤트만 표준, 메트릭·샘플링·실험 태그는 `aiops.*` | 표준에 평가 메트릭 없음. 표준 네임스페이스에 임의 키 금지 (ADR-0018 원칙) |

## 검토한 대안

| 대안 | 장점 | 단점 | 기각 사유 |
|------|------|------|-----------|
| 평가 입력을 페이로드에 도구 원본 동봉 | 재조회 없이 자족, 보존 한계 무관 | agent-service 상태·페이로드 크기 증가, 앱 변경 | 재조회로 충분함을 실측. 보존 밖 케이스는 골든셋 재주입으로 해결 |
| 평가 입력을 Tempo `execute_tool` 스팬 출력에서 조회 | 이미 수집 중 | K8s 는 NO_CONTENT 캡처라 형상 의존 | 형상별 동작 차이 |
| agent-service 안의 세 번째 컨슈머 | 재활용 최대, 배포 0 | Judge 비용이 agent-service 에 섞임, "응답 경로 무영향"을 프로세스 공유 상태에서 주장 | 비용 분리·장애 격리 |
| evaluation-service 자체 DB | control-plane 무변경 | 리뷰 큐·승격·Slack 이 관제 밖으로 분산 | human-in-the-loop 는 control-plane 역할 |
| 게이트웨이 단일 배정(원안) | 배정 지점 하나 | 게이트웨이는 incident_id 를 모르고 프롬프트를 바꿀 수 없음 | 성립 불가 |
| 헤더로 임의 모델 지정 허용 | 구현 단순 | 예산·라우팅 정책 우회 | 정의된 variant 만 허용으로 대체 |
| Langfuse Scores API 를 주 저장소로 | UI 즉시 표시 | compose 전용, OTLP 미지원, 벤더 종속 | 선택 사항으로 강등 |
| 공식 평가기 패키지 대기 | 자체 구현 회피 | 존재하지 않음 (2026-09-09) | — |

## 결과

쉬워지는 것: 프롬프트·모델 변경을 variant 로 등록하면 같은 Judge 가 같은 기준으로 채점해 사전 기준(Faithfulness +0.05 이상 && 비용 +30% 이내)으로 판정한다. 저품질 케이스가 사람 검토를 거쳐 골든셋에 쌓이므로 평가 자산이 운영과 함께 자란다. 품질·비용·지연이 같은 trace 축(incident id)에 붙는다.

어려워지는 것: 컴포넌트 +1 (compose·차트·CI·auth 클라이언트·게이트웨이 규칙), Judge 호출 비용 (샘플링 프로파일로 상한), 표본이 작아 실험 신뢰구간이 넓다 (부트스트랩 + "보류" 결론 허용). 평가 컨벤션이 Development 라 이름 변경 시 mapping 문서 pin 점검 절차를 따른다.

되돌리기: evaluation-service 와 토픽·테이블을 제거하면 응답 경로는 무영향이다. 골든셋 파일과 실험 리포트는 남는다.

재평가 조건: GenAI 평가 컨벤션 안정 릴리즈, 공식 평가기 패키지 등장, 실 사용자 트래픽 발생(샘플링 프로파일 재산정). 확정 시 추가(아래 추가 사항): 해시 배정이 작은 표본에서 치우치면 층화·교대 배정으로, Judge 점수가 상단에 포화해 사전 기준(+0.05)이 도달 불가능해지면 앵커 세분화 또는 Actionability 병행 기준으로.

## 추가 사항 (2026-09-16): 승인 — 3층 구현과 실측 근거

DAY 45~50 (2026-09-09~16) 에 세 층을 구현하고 실험 1건을 끝까지 돌린 뒤 확정한다. 단일 원천은 계속 `docs/quality-evaluation.md`, 실험 기록은 `docs/experiments/`.

| 층 | 구현 | 실측 근거 |
|---|---|---|
| ① 골든셋 | 라벨링 시트 20건(`evaluation-service/golden/labeling/`) → `build_golden.py` → `v1.jsonl`, Judge baseline `judge-baseline.json`, 회귀 `@pytest.mark.golden` + `golden-regression.yml`(주 1회·수동) | Judge 프롬프트 v2: 전체 MAE 0.122(≤0.15), 앵커 일치 68.3%(≥60%), false pass 1(보존 밖 합성 발화 — 온라인에서는 재현 안 됨), 반복 표준편차 F/A/S 0.066/0.014/0.007. v1 은 MAE 0.343 — 재조회 요약에 없는 관측을 근거 없음으로 매기던 과잉 감점을 루브릭에서 정정 |
| ② 온라인 Judge | `evaluation-service`(별도 컨테이너, JWT client_id = 비용 라벨) — 층화·결정론 샘플링, 시간창 재조회, `gen_ai.evaluation.result` 이벤트 + `aiops.evaluation.*` 메트릭, control-plane V5~V7 저장·리뷰 큐·승격(`promote_golden.py`), Slack 검토 요청, SLO 룰 4종(`AiopsFaithfulnessLow`·`…7d`·`AiopsJudgeErrorRate`·`AiopsVariantFaithfulnessLow`) | Judge 1건 ≈ 0.012 USD(2026-09-11 62건 0.74 USD). 저품질 케이스가 검토 요청 → 사람 라벨 → 시트 승격으로 이어지는 경로 실증. 평가 스팬은 원 실행 trace 로 link — 품질·비용·지연이 한 incident 축 |
| ③ 실험 | agent-service 배정(`experiments.yml`·`ExperimentAssigner` 해시) + 게이트웨이 `X-Experiment-Variant` 오버라이드(정의된 variant 만) + 비용 라벨 `variant` + 평가 태그 → 요약·목록 API → `experiment_report.py`(부트스트랩 95% CI·사전 기준) | 실험 1(프롬프트 v1→v2, 15회차·2시간 54분·2.85 USD): F +0.03, CI −0.14~+0.20 → **보류**. 실험 2(모델 sonnet→haiku, 재생 짝 11건): F −0.08, CI −0.25~+0.08, 비용 −62%, 저품질률 55% vs 18% → **보류**. 둘 다 기본값 유지 — "느낌으로 바꾸지 않는다" 가 사전 기준으로 작동한 첫 사례 (`docs/experiments/2026-09-16-analysis-prompt-v2.md`) |

확정하면서 바뀐 것과 배운 것:

- **Judge 신뢰성 관리 5종**을 절차로 고정한다 — 다른 프로바이더 모델(gpt-5.6-terra), 캐시 우회(`gateway.cache=BYPASS` — 캐시에 맞으면 분산이 0 이라 일관성 측정이 무의미), 반복 3회 분산 측정, 주 1회 골든셋 회귀(MAE 악화 0.05 초과·false pass 증가 시 실패), 보고서 본문의 구조적 분리(`wrap_untrusted` — [ADR-0017](0017-prompt-injection-defense.md) 추가 사항).
- **표본이 병목이다**. 15회차에서 해시 배정이 A 11·B 4 로 치우쳤다(배정기 편향 없음 — 합성 2만 건 B 0.498, 이항 p=0.059). CI 폭이 ±0.17 이라 "보류" 가 기본 결론이 된다. 데모 트래픽에서 실험은 며칠 단위로 표본을 모아야 하고, 작은 표본이면 층화·교대 배정이 맞다(재평가 조건 추가).
- **Judge 는 상단에 몰린다** — 사람 F 라벨도 Judge F 도 1.0 에 밀집(골든셋 ρ 미달, 실험 1 A 11건 중 8건이 1.0). 사전 기준 +0.05 는 이 분포에서 넘기 어렵다.
- **실험 중 감지는 자동, 승자 판정은 사람** — 저품질 알림에 실험 좌표, variant 룰이 처리군 악화를 알리지만 중단·승격은 `experiments.yml` 을 사람이 바꾼다(hot reload 없음). 자동 승격을 넣지 않은 이유: 표본 15건·Judge 지연·검증 층 자체의 신뢰성.
- **일 예산이 실험 설계를 제약한다** — 회차당 0.21 USD 라 양쪽 arm 재생을 포기했다. 예산 100% 도달 시 다운그레이드가 실험 자체를 오염시키므로 실험일에는 예산 대비 회차 수를 먼저 계산한다.
- **재생(replay)의 한계** — 도구가 "지금" 을 조회해 원본 시각으로 앵커되지 않는다. 정착 창 안 재생으로 완화했지만 시각 앵커가 있는 도구 인자(시간 범위)가 근본 해법이다(이월). 호스트에서 돌리는 스크립트는 OTLP 엔드포인트·종료 전 flush 가 없으면 스팬이 조용히 빠진다.

되돌리기·재평가 조건은 본문과 같다. 실험 1 은 `status: concluded` 로 닫았다 — 데모 트래픽의 자연 유입으로는 표본이 모이지 않아 열어 둘 의미가 없고, 표본 보강은 active 로 되돌린 뒤 드라이버(`RESUME=1 experiment-run.sh analysis-prompt-v2 10`)로 한다. 종료·승자 결정은 사람.
