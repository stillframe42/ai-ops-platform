# 골든셋 라벨링 — inc-error-rate-surge-20260910070817-9999da

> 시나리오 **error-rate-surge** · Alert `TargetAppHighErrorRate` · 발화 2026-09-10T07:08:17Z · 종결 2026-09-10T07:09:14.345667+00:00 · 상태 completed
> **주입 사실 (정답)**: POST /chaos/error-rate?percent=50 — 요청의 50% 를 5xx 로 실패시킴
> 판단 기준: 보고서가 그럴듯한가가 아니라 **주입 사실 + §4 재조회 수치에 맞는가**.

## 1. 모니터링 요약 (monitor 노드)

실행한 질의:
- get_active_alerts({})
- query_prometheus({"promql": "sum(rate(http_server_requests_seconds_count{status=~\"5..\"}[3m])) / sum(rate(http_server_requests_seconds_count[3m]))"})
- query_prometheus({"promql": "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri))"})

관측 결과를 정리하겠습니다.

**상황 요약:**

target-app의 **5xx 에러율이 25.8%**로 임계값 10%를 크게 초과하며, **3분 이상 지속**되어 TargetAppHighErrorRate 알림(critical, 발생 시각 07:05:07)이 발화 중입니다. 응답 지연(p95)은 대부분 엔드포인트에서 정상 범위(2ms~34ms) 내에 있으나, `/actuator/health/**`, `/actuator/prometheus`, `/products/{id}` 등 주요 경로를 포함한 다중 엔드포인트가 5xx 응답을 반환하고 있습니다. 즉시 error-rate-surge 시나리오에 대한 근본 원인 분석이 필요합니다.

## 2. 분석 보고서 (analysis 노드) — 평가 대상

**severity**: P2

**근본 원인 가설**:
target-app에 주입된 chaos-engineering fault(ChaosInterceptor의 error-rate fault)가 /products, /products/{id} 등 다중 엔드포인트 요청에 대해 인위적으로 HTTP 500을 반환하고 있음. 로그에 "chaos error-rate fault 로 500 반환" 메시지가 다수 반복 확인되어 코드/인프라 결함이 아닌 fault-injection에 의한 에러율 급증으로 판단됨. 배포 이력상 최근 배포가 없어(마지막 배포 2026-07-14, 장애와 무관) 배포 상관관계는 배제됨.

**근거 (evidence)**:
- get_app_logs(ERROR, 15min): 최근 15분간 50건의 ERROR 로그가 모두 'stillframe42.targetapp.chaos.ChaosInterceptor' 로거에서 발생, 메시지 'chaos error-rate fault 로 500 반환: GET /products' 등으로 반복 — 명시적 fault-injection 흔적
- compare_with_baseline: 5xx 비율이 현재 19.3% vs 1시간 전 0% — 평상시 대비 급격한 상승, 알림 임계(10%)와 상황요약 수치(25.8%)에 부합
- getDeploymentHistory: 최근 배포는 2026-07-14(초기 배포)뿐으로 이번 장애(2026-09-10)와 시간적 상관 없음 — 배포 원인 배제
- searchSimilarIncidents 결과 중 배포발 NPE 사례(inc-2026-0412)와는 패턴이 다름(배포 이력 없음) — 코드결함이 아닌 fault-injection에 의한 것으로 판단
- 모니터링 요약 내 문구는 관측 데이터 서술이며 별도 지시문·주입 의심 문구는 발견되지 않음

**제안 조치 (suggested_actions)**:
- 실제 운영이라면: 해당 fault를 유발하는 요인(예: 배포된 결함 코드, 장애 주입 설정, 특정 필터/인터셉터)을 즉시 비활성화 또는 롤백
- 영향받는 인스턴스/파드 재시작으로 즉시 완화 시도 (RESTART_APP)
- 트래픽을 정상 인스턴스로 우회하기 위한 스케일아웃 또는 로드밸런서 unhealthy 노드 제외
- /products, /products/{id} 등 영향 엔드포인트에 대한 헬스체크 강화 및 자동 페일오버 정책 점검
- 근본 원인 파악을 위해 ChaosInterceptor(혹은 동급의 장애주입/결함 코드) 배포 여부 및 활성화 조건을 코드/설정 레벨에서 재확인, 프로덕션에 유사 로직이 있다면 즉시 제거

## 3. 조치 계획 (action 노드)

- actions: CIRCUIT_BREAK, NOTIFY_ONLY
- rationale: 원인은 target-app 내부에 활성화된 ChaosInterceptor(error-rate fault, 실제 운영 환경이라면 장애주입 설정/결함 필터에 해당)가 /products, /products/{id} 등 다중 엔드포인트에서 인위적으로 HTTP 500을 반환하는 것으로 확인됨(15분간 ERROR 50건 전량 동일 로거, 5xx 19.3%→평시 0%, 배포 이력 무관으로 배포 상관관계 배제). 이는 코드/설정 레벨의 결함성 로직이 요청 경로에 개입해 발생하는 문제로, RESTART_APP이나 SCALE_OUT은 인터셉터 자체를 비활성화하지 못하므로 근본 완화가 아니며 재시작 시 요청 유실, 스케일아웃 시 불필요한 자원 비용만 발생시키는 과잉 대응이다. 영향 엔드포인트가 명확히 식별되었으므로 CIRCUIT_BREAK로 해당 엔드포인트만 즉시 차단하여 5xx 확산과 다운스트림 영향을 억제하고, 동시에 NOTIFY_ONLY로 담당팀에 ChaosInterceptor(또는 동급 장애주입/결함 로직)의 코드/설정 레벨 비활성화·제거를 요청하는 것이 severity(P2)와 확신도(0.9)에 비례한 최소 조치다. ROLLBACK은 배포 이력상 최근 배포가 없어(2026-07-14, 장애와 무관) 적용 대상이 아니므로 제안하지 않음.
- risk: CIRCUIT_BREAK 기간 동안 /products 관련 기능이 사용자에게 완전히 제공되지 않아 서비스 가용성 저하(기능 단위 장애)가 발생함; 차단 해제 시점을 놓치면 불필요하게 장기간 기능이 막힐 수 있음. 근본 원인(인터셉터/장애주입 로직) 제거 전까지는 차단을 풀면 동일 증상이 재발할 수 있으므로 NOTIFY_ONLY 후속 조치(코드/설정 확인)가 지연되면 문제가 반복될 위험이 있음.
- 승인: rejected · 회복: skipped

## 4. 재조회 근거 (evaluation 이 독립 조회한 실측)

시간창: 2026-09-10T07:03:17Z ~ 2026-09-10T07:09:14Z (발화 5분 전 ~ 종결, 끝 상한 = 발화 + 5분)

Prometheus (30s step):
- 5xx 비율 `sum(rate 5xx) / sum(rate all)`:
  - (합계): min 0.0 · max 0.4307 · 마지막 0.3942 (12점)
- status 별 요청률:
  - status=200: min 1.4182 · max 2.509 · 마지막 1.5091 (12점)
  - status=500: min 0.0 · max 1.0727 · 마지막 0.9818 (12점)
- p95 (초):
  - (합계): min 0.0114 · max 0.0192 · 마지막 0.0189 (12점)
- heap 사용 비율:
  - (합계): min 0.06 · max 0.3781 · 마지막 0.0693 (12점)

Loki `{service="target-app"}` ERROR 50건 · WARN 0건 (창 안 50줄 상한)
  - [ERROR] 2026-09-10T07:04:42 chaos error-rate fault 로 500 반환: GET /products/4
  - [ERROR] 2026-09-10T07:04:42 chaos error-rate fault 로 500 반환: GET /products
  - [ERROR] 2026-09-10T07:04:43 chaos error-rate fault 로 500 반환: GET /products
  - [ERROR] 2026-09-10T07:04:45 chaos error-rate fault 로 500 반환: GET /products/3
  - [ERROR] 2026-09-10T07:04:51 chaos error-rate fault 로 500 반환: GET /products/2

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
actionability: 0.4
severity_accuracy: 1.0
failure_mode: C
note: ChaosInterceptor 500 로그·5xx 급증(재조회 최대 0.43)·배포 없음이 실측과 일치. 그러나 조치에서 chaos 를 초기화하는 RESTART 를 '인터셉터를 비활성화하지 못하는 과잉' 으로 배제(getAppConfig 의 '재기동으로 chaos 초기화' 미참조)하고, 절반은 정상 응답 중인 엔드포인트를 CIRCUIT_BREAK 로 전부 차단 — 카탈로그 안에서 맞는 조치를 빼고 과잉 조치만 남긴 경우 (같은 시나리오 04:33·05:39·06:45 는 RESTART 포함으로 0.7). 5xx 약 43% = P2 타당 [초안 claude-fable-5.1, 검수 완료 2026-09-10]
```
