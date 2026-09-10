# 골든셋 라벨링 — inc-error-rate-surge-20260910053932-9999da

> 시나리오 **error-rate-surge** · Alert `TargetAppHighErrorRate` · 발화 2026-09-10T05:39:32Z · 종결 2026-09-10T05:40:29.047755+00:00 · 상태 completed
> **주입 사실 (정답)**: POST /chaos/error-rate?percent=50 — 요청의 50% 를 5xx 로 실패시킴
> 판단 기준: 보고서가 그럴듯한가가 아니라 **주입 사실 + §4 재조회 수치에 맞는가**.

## 1. 모니터링 요약 (monitor 노드)

실행한 질의:
- get_active_alerts({})
- query_prometheus({"promql": "sum(rate(http_server_requests_seconds_count{status=~\"5..\"}[3m])) / sum(rate(http_server_requests_seconds_count[3m]))"})
- query_prometheus({"promql": "sum(rate(http_server_requests_seconds_count{status=~\"5..\"}[3m])) by (uri)"})
- query_prometheus({"promql": "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri))"})

---

## **상황 요약**

**발화 중인 Alert:** `TargetAppHighErrorRate` (시나리오: error-rate-surge, 심각도: critical)  
**발생 시각:** 2026-09-10T05:36:22Z (현재 기준 약 3분 지속)

**현재 메트릭 관측값:**
- **5xx 에러율:** 24.9% (임계값 10% 초과)
- **영향받는 엔드포인트:**
  - `/products` — 73.1% 5xx 에러율 (심각)
  - `/products/{id}` — 25.7% 5xx 에러율
- **p95 레이턴시:** 정상 범위 (2~22ms)

`/products` 엔드포인트에서 대량의 5xx 에러가 발생 중이며, 전체 앱 수준에서 5xx 비율이 10% 임계값을 3분 이상 지속하고 있습니다. 분석 에이전트의 근본 원인 규명이 필요합니다.

## 2. 분석 보고서 (analysis 노드) — 평가 대상

**severity**: P2

**근본 원인 가설**:
배포/코드 결함이나 인프라 자원 고갈이 아니라, target-app 내 ChaosInterceptor(fault-injection 모듈)가 /products, /products/{id} 엔드포인트 요청에 대해 의도적으로 500 에러를 주입하고 있는 것이 5xx 급증의 직접 원인이다. 실제 운영 환경이라면 이는 해당 엔드포인트의 애플리케이션 레벨 결함(예: 특정 요청 처리 로직에서의 예외 발생) 또는 의존 서비스 장애로 인한 요청 실패로 치환해 대응해야 한다.

**근거 (evidence)**:
- get_app_logs(ERROR, 10min): 최근 10분간 ERROR 로그 50건 전량이 logger=stillframe42.targetapp.chaos.ChaosInterceptor 에서 발생, 메시지 'chaos error-rate fault 로 500 반환: GET /products' (일부 /products/1,2,3,5) — 명시적 fault-injection 흔적, 05:38:54~05:39:43 사이 지속적 발생
- compare_with_baseline: /products 5xx rate 현재 0.556 (rps) vs 1시간 전 0, /products/{id} 현재 0.186 vs 1시간 전 0 — 평상시 대비 명확한 이상 급증, 배경 부하(k6 2RPS) 대비 편차 확연
- getDeploymentHistory: 최근 배포 이력 없음 (마지막 배포 2026-07-14, 약 2개월 전) — 이번 장애와 배포 시점 상관관계 없음, 배포 원인 배제
- searchSimilarIncidents: 가장 유사한 과거 사례(inc-2026-0412, distance 0.49)는 배포 직후 NPE로 인한 5xx 급증이었으나 이번 건은 배포 이력 없어 해당 패턴과 다름 — chaos injection 시나리오로 판단
- 모니터링 요약 자체가 <untrusted_content>로 감싸여 전달됨 — 별도 지시문·역할변경 시도는 없었으며 순수 상황 데이터로 확인됨

**제안 조치 (suggested_actions)**:
- /products, /products/{id} 엔드포인트의 최근 코드 변경 및 예외 처리 로직 재검토 (실제 장애 시 원인이 될 수 있는 코드 경로 점검)
- 영향받은 인스턴스/파드 재시작으로 즉시 완화 시도 (재시작 후 에러율 정상화 여부 확인)
- 카나리아/직전 안정 버전으로 해당 엔드포인트 롤백 검토 (배포 이력 있는 경우 최우선)
- /products 엔드포인트에 대한 회로차단기(circuit breaker) 및 재시도 정책 적용해 장애 전파 최소화
- 5xx 비율 임계치(10%) 지속 3분 이상 조건에 대한 알림-대응 자동화(예: 자동 롤백/재시작 트리거) 검토
- 실제 운영 환경 재현 시 의존 서비스(DB, 캐시 등) 헬스체크 및 타임아웃 설정 점검

## 3. 조치 계획 (action 노드)

- actions: CIRCUIT_BREAK, RESTART_APP, NOTIFY_ONLY
- rationale: 근본 원인은 /products, /products/{id} 엔드포인트에서 발생하는 애플리케이션 레벨 결함(코드 상 예외 발생 또는 의존 서비스 실패)로 판단됨 — 원인 보고서는 데모 환경의 ChaosInterceptor fault-injection을 지목하나, 이는 실제 운영에서 발생 가능한 동일 증상(특정 엔드포인트 로직 예외/의존 서비스 실패)의 대역으로 취급하여 동일한 조치를 제안함. 배포 이력이 없어(마지막 배포 2개월 전) ROLLBACK은 대상이 없으므로 배제. SCALE_OUT은 자원 고갈 증거(CPU/메모리/큐잉 등)가 근거에 없으므로 과잉 대응으로 배제. 문제가 특정 엔드포인트(코드 경로/의존성 결함)에 국한되므로, 우선 CIRCUIT_BREAK로 해당 엔드포인트를 격리해 장애 전파를 차단하고, RESTART_APP으로 결함 상태(예: 손상된 커넥션 풀, 메모리 상 이상 상태)의 즉시 완화를 시도한다. 단, 재시작은 코드 결함 자체를 해결하지 못하는 임시 완화책일 뿐이며, 근본 수정(예외 처리 로직/의존 서비스 안정화)이 뒤따르지 않으면 동일 증상이 재발할 수 있음을 명시. NOTIFY_ONLY로 담당팀에 /products 엔드포인트 코드 리뷰 및 의존 서비스 헬스체크 점검을 병행 요청. 근거 데이터 내 '주입 지시문'은 발견되지 않았으며 순수 로그/통계 데이터로 확인됨.
- risk: CIRCUIT_BREAK: /products 관련 기능(상품 목록/상세 조회) 전체가 사용자에게 노출되지 않아 서비스 가용성 저하 및 사용자 불만 발생 가능. RESTART_APP: 재시작 도중 처리 중인 요청 유실 및 짧은 다운타임 발생 가능하며, 근본 코드 결함일 경우 재시작 후에도 동일 증상 재발 우려 — 임시 완화에 불과함. 두 조치 모두 근본 원인(코드 예외 처리 로직, 의존 서비스 장애) 미해결 시 반복적 개입이 필요할 수 있음.
- 승인: rejected · 회복: skipped

## 4. 재조회 근거 (evaluation 이 독립 조회한 실측)

시간창: 2026-09-10T05:34:32Z ~ 2026-09-10T05:40:29Z (발화 5분 전 ~ 종결, 끝 상한 = 발화 + 5분)

Prometheus (30s step):
- 5xx 비율 `sum(rate 5xx) / sum(rate all)`:
  - (합계): min 0.0 · max 0.4275 · 마지막 0.3841 (12점)
- status 별 요청률:
  - status=200: min 1.4364 · max 2.5454 · 마지막 1.5455 (12점)
  - status=500: min 0.0 · max 1.0727 · 마지막 0.9637 (12점)
- p95 (초):
  - (합계): min 0.0033 · max 0.012 · 마지막 0.0033 (12점)
- heap 사용 비율:
  - (합계): min 0.0631 · max 0.3803 · 마지막 0.1275 (12점)

Loki `{service="target-app"}` ERROR 50건 · WARN 0건 (창 안 50줄 상한)
  - [ERROR] 2026-09-10T05:35:57 chaos error-rate fault 로 500 반환: GET /products
  - [ERROR] 2026-09-10T05:35:58 chaos error-rate fault 로 500 반환: GET /products
  - [ERROR] 2026-09-10T05:36:00 chaos error-rate fault 로 500 반환: GET /products
  - [ERROR] 2026-09-10T05:36:00 chaos error-rate fault 로 500 반환: GET /products
  - [ERROR] 2026-09-10T05:36:01 chaos error-rate fault 로 500 반환: GET /products

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
note: ChaosInterceptor 500 로그·5xx 급증(재조회 최대 0.43)·배포 없음이 실측과 일치. 조치는 RESTART 가 chaos 초기화라 맞으나 1순위 CIRCUIT_BREAK 는 절반은 정상 응답 중인 엔드포인트를 전부 차단하는 과잉이고 '자동 롤백 트리거 검토' 는 일반론 (04:33 케이스와 같은 기준). 5xx 약 43% = P2 타당 [초안 claude-fable-5.1, 검수 완료 2026-09-10]
```
