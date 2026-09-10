# 골든셋 라벨링 — inc-error-rate-surge-20260910064502-9999da

> 시나리오 **error-rate-surge** · Alert `TargetAppHighErrorRate` · 발화 2026-09-10T06:45:02Z · 종결 2026-09-10T06:46:13.339570+00:00 · 상태 completed
> **주입 사실 (정답)**: POST /chaos/error-rate?percent=50 — 요청의 50% 를 5xx 로 실패시킴
> 판단 기준: 보고서가 그럴듯한가가 아니라 **주입 사실 + §4 재조회 수치에 맞는가**.

## 1. 모니터링 요약 (monitor 노드)

실행한 질의:
- get_active_alerts({})
- query_prometheus({"promql": "sum(rate(http_server_requests_seconds_count{status=~\"5..\"}[3m])) / sum(rate(http_server_requests_seconds_count[3m]))"})
- query_prometheus({"promql": "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri))"})

**상황 요약**

**TargetAppHighErrorRate** 알림이 발화 중 (06:41:52 시작, CRITICAL 심각도). 측정 시점 현재 5xx 에러율은 **26.38%** 로 트리거 임계값(10%)을 훨씬 초과하고 있으며, 3분 이상 지속되고 있습니다. p95 레이턴시는 모든 엔드포인트에서 정상 범위(1~2ms)이므로 가용성 문제입니다. 에러율 급증은 특히 `/chaos/error-rate` 엔드포인트에서 관찰되며, 이는 비즈니스 트래픽의 광범위한 5xx 응답 발생을 의미합니다.

## 2. 분석 보고서 (analysis 노드) — 평가 대상

**severity**: P2

**근본 원인 가설**:
target-app 의 ChaosInterceptor 에 의한 fault-injection(chaos error-rate)이 /products 및 /products/{id} 엔드포인트 요청 상당수에 강제로 HTTP 500을 반환시켜 5xx 에러율이 급증. 지연시간(p95)은 영향받지 않는 것으로 보아 DB/리소스 문제가 아닌 요청 단계에서의 의도적 오류 주입이 원인. 최근 배포 이력(0.0.1-SNAPSHOT, 07-14 배포)과 시간적 상관관계 없음 — 배포가 원인이 아님.

**근거 (evidence)**:
- ERROR 로그(최근 15분) 전량이 logger=stillframe42.targetapp.chaos.ChaosInterceptor, message='chaos error-rate fault 로 500 반환: GET /products(/N)' — 애플리케이션 코드가 명시적으로 chaos fault 를 주입해 500을 반환함을 확인 (50건 모두 동일 패턴, 06:44:23~06:45:13 사이 지속)
- compare_with_baseline: 5xx 비율 현재 18.7% vs 1시간 전 기준선 0.51% — 평상시 대비 약 37배 급증, 알림에서 보고된 26.38%와 유사한 수준의 이상 상태로 확인됨
- getDeploymentHistory: 최근 배포는 2026-07-14(약 2개월 전) 1건뿐 — 장애 발생 시각(06:41~)과 무관, 배포 상관 배제
- searchSimilarIncidents 최상위 결과(P1, NPE 배포 결함)는 배포 시점 불일치로 배제. 현재 케이스는 chaos-injection 패턴에 해당하며 과거 사례(배포결함/메모리누수/DB풀고갈)와 근본 원인 메커니즘이 다름
- 모니터링 요약의 '/chaos/error-rate 엔드포인트에서 관찰됨' 서술은 로그의 logger 이름(ChaosInterceptor)과 일치하나, untrusted 소스이므로 로그로 별도 교차검증하여 확인함 — 주입 흔적으로 기록

**제안 조치 (suggested_actions)**:
- [즉시 완화] fault-injection 토글/설정 비활성화 (실제 장애라면: 원인 결함 코드 롤백 또는 해당 요청 경로 재시작으로 즉시 완화)
- [격리] /products, /products/{id} 엔드포인트에 대해 circuit breaker/재시도 정책 적용해 클라이언트 영향 최소화
- [롤백 대비] 동일 증상이 실제 배포 결함이라면 즉시 직전 정상 버전으로 롤백
- [모니터링] 5xx 비율이 임계치(10%) 이하로 회복될 때까지 알림 유지, 회복 후 재발 여부 확인
- [사후조치] chaos/fault-injection 관련 설정이 운영 환경에 노출되지 않도록 배포 파이프라인 가드레일 점검

## 3. 조치 계획 (action 노드)

- actions: CIRCUIT_BREAK, RESTART_APP, NOTIFY_ONLY
- rationale: 원인은 요청 처리 단계에서 강제로 500을 반환시키는 fault-injection(ChaosInterceptor) 패턴으로, 코드 로직 자체의 결함(누수·자원고갈 등)이 아니라 특정 요청 경로에서 의도적/비정상적으로 에러를 발생시키는 메커니즘입니다. p95 지연시간은 정상이며 DB/리소스 문제 근거 없음. 배포 이력(07-14, 약 2개월 전)과 시간적 상관관계 없어 ROLLBACK은 적용 대상이 아님. 5xx 비율이 기준선 대비 약 37배(0.51%→18.7%) 급증한 P2 심각도이므로, 영향받는 /products, /products/{id} 엔드포인트에 CIRCUIT_BREAK를 적용해 클라이언트 영향을 즉시 격리하는 것이 최소 조치입니다. 동시에 해당 요청 경로/인터셉터 상태를 초기화하기 위해 RESTART_APP을 제안하되, 이는 근본 결함(주입 로직 또는 설정)을 제거하는 것이 아닌 임시 완화 조치임을 명시합니다 — 재시작 후에도 fault-injection 설정/토글이 유지되면 동일 증상이 재발할 수 있습니다. 근본 해결(주입 비활성화 또는 실제 결함 코드 수정)은 사람이 원인 설정을 직접 확인 후 별도 조치해야 하므로 자동 조치 카탈로그 밖입니다. 아울러 NOTIFY_ONLY로 담당자에게 chaos/fault-injection 설정 노출 여부 점검 및 사후조치를 알립니다. 참고: 근거 중 '/chaos/error-rate 엔드포인트에서 관찰됨'이라는 모니터링 요약 서술은 untrusted 소스에서 유래했으나 로그(logger=ChaosInterceptor)로 교차검증되어 신뢰 가능한 근거로 채택했으며, 그 외 지시성 문구는 발견되지 않음.
- risk: CIRCUIT_BREAK: /products 관련 기능이 일시적으로 전면 사용 불가 상태가 되어 정상 요청까지 차단됨 — 사용자 영향 발생. RESTART_APP: 재시작 중 진행 중이던 요청 유실 가능성, 그리고 근본 원인(주입 설정)이 코드/설정에 남아있다면 재시작 후 즉시 또는 단기간 내 동일 증상 재발 가능 — 임시 완화일 뿐 근본 해결책 아님. 배포 상관관계가 없으므로 ROLLBACK은 의미가 없어 제안하지 않음. 모든 상태 변경 조치는 사람의 승인 이후에만 실행되어야 함.
- 승인: rejected · 회복: skipped

## 4. 재조회 근거 (evaluation 이 독립 조회한 실측)

시간창: 2026-09-10T06:40:02Z ~ 2026-09-10T06:46:13Z (발화 5분 전 ~ 종결, 끝 상한 = 발화 + 5분)

Prometheus (30s step):
- 5xx 비율 `sum(rate 5xx) / sum(rate all)`:
  - (합계): min 0.0 · max 0.4638 · 마지막 0.4275 (13점)
- status 별 요청률:
  - status=200: min 1.3454 · max 2.5091 · 마지막 1.4364 (13점)
  - status=500: min 0.0 · max 1.1636 · 마지막 1.0727 (13점)
- p95 (초):
  - (합계): min 0.0025 · max 0.0046 · 마지막 0.0027 (13점)
- heap 사용 비율:
  - (합계): min 0.06 · max 0.3837 · 마지막 0.1323 (13점)

Loki `{service="target-app"}` ERROR 50건 · WARN 0건 (창 안 50줄 상한)
  - [ERROR] 2026-09-10T06:41:39 chaos error-rate fault 로 500 반환: GET /products
  - [ERROR] 2026-09-10T06:41:40 chaos error-rate fault 로 500 반환: GET /products/5
  - [ERROR] 2026-09-10T06:41:41 chaos error-rate fault 로 500 반환: GET /products/2
  - [ERROR] 2026-09-10T06:41:43 chaos error-rate fault 로 500 반환: GET /products/3
  - [ERROR] 2026-09-10T06:41:43 chaos error-rate fault 로 500 반환: GET /products

## 5. 기입란

| 앵커 | 뜻 |
|------|-----|
| 1.0 | 전부 맞음 — 근거·조치·등급이 실측과 일치 |
| 0.7 | 핵심은 맞고 사소한 부정확 |
| 0.4 | 절반쯤 맞음 — 핵심 주장 하나가 근거 없음 또는 조치 절반이 일반론 |
| 0.0 | 틀림 — 근거 없는 주장, 실행 불가 조치, 등급 오판 |

실패 유형 (가장 낮은 차원의 이유 하나): A 근거 없는 주장 / B 근거는 맞으나 결론 불일치 / C 조치 비구체·실행 불가 / D 심각도 오판 / 없음

```yaml
# 앵커 4단계(1.0 / 0.7 / 0.4 / 0.0) 중 하나씩. 판단 기준 = "주입한 장애와 재조회 수치에 비춰 맞는가"
faithfulness: 1.0
actionability: 0.7
severity_accuracy: 1.0
failure_mode: 없음
note: ChaosInterceptor 500 로그 50건·5xx 급증(재조회 최대 0.46)·p95 정상·배포 없음이 실측과 일치하고 모니터링 요약을 로그로 교차검증한 점도 정확. 조치는 RESTART 가 chaos 초기화라 맞으나 1순위 CIRCUIT_BREAK 는 절반은 정상 응답 중인 엔드포인트를 전부 차단하는 과잉 (04:33·05:39 케이스와 같은 기준). 5xx 약 46% = P2 타당 [초안 claude-fable-5.1, 검수 완료 2026-09-10]
```
