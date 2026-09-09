# ADR-0019: LLM 품질의 지속 검증 체계 — 골든셋 / 온라인 Judge / 실험 3층

- 상태: 제안됨 (2026-09-09 초안 — 파이프라인 구현·실험 리포트와 함께 확정 예정)
- 날짜: 2026-09-09

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

재평가 조건: GenAI 평가 컨벤션 안정 릴리즈, 공식 평가기 패키지 등장, 실 사용자 트래픽 발생(샘플링 프로파일 재산정).
