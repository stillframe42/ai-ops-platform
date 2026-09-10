# 골든셋 라벨링 — inc-error-rate-surge-20260910043332-9999da

> 시나리오 **error-rate-surge** · Alert `TargetAppHighErrorRate` · 발화 2026-09-10T04:33:32Z · 종결 2026-09-10T05:16:38.944773+00:00 · 상태 completed
> **주입 사실 (정답)**: POST /chaos/error-rate?percent=50 — 요청의 50% 를 5xx 로 실패시킴
> 판단 기준: 보고서가 그럴듯한가가 아니라 **주입 사실 + §4 재조회 수치에 맞는가**.

## 1. 모니터링 요약 (monitor 노드)

실행한 질의:
- get_active_alerts({})
- query_prometheus({"promql": "sum(rate(http_server_requests_seconds_count{status=~\"5..\"}[3m])) / sum(rate(http_server_requests_seconds_count[3m]))"})
- query_prometheus({"promql": "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri))"})
- query_prometheus({"promql": "sum(rate(http_server_requests_seconds_count{status=~\"5..\"}[3m])) by (uri)"})

**현재 상황 요약:**

target-app에서 **5xx 에러율이 27.84%로 급증**하여 임계값(10% / 3분)을 초과해 **TargetAppHighErrorRate 알림이 발화**된 상태입니다. 엔드포인트별로는 **/products 경로에서 82.3%, /products/{id} 경로에서 28.6%의 높은 5xx 에러율**을 기록 중입니다. p95 레이턴시는 모든 엔드포인트에서 정상 범위(3ms 이하)를 유지하고 있으며, 비즈니스 API의 가용성이 심각하게 저하된 상태입니다.

## 2. 분석 보고서 (analysis 노드) — 평가 대상

**severity**: P2

**근본 원인 가설**:
target-app 내 ChaosInterceptor 에 의한 fault-injection(error-rate 주입)이 /products, /products/{id} 엔드포인트에 5xx(500) 오류를 강제로 반환시키고 있음. 배포 이력상 최근 배포는 없어(초기 배포 2026-07-14, 약 2개월 전) 배포와의 상관은 없음. 코드 레벨 결함이나 인프라 장애가 아니라 의도적 chaos fault 주입이 현재 5xx 급증의 직접 원인.

**근거 (evidence)**:
- get_app_logs(ERROR, 10m): stillframe42.targetapp.chaos.ChaosInterceptor 로거가 다수(50건 전량) 'chaos error-rate fault 로 500 반환: GET /products' 및 'GET /products/{id}' 메시지를 반복 출력 — 명시적 fault-injection 흔적 (주입 의심 문구 아님, 로거명 자체가 chaos 모듈)
- compare_with_baseline: 현재 /products 5xx 비율 39.1%, /products/{id} 5xx 비율 40.8% — baseline_1h_ago 데이터 없음(빈 배열)으로 1시간 전 대비 상대비교는 불가하나 절대 수치 자체가 매우 높음
- 모니터링 요약상 /products 82.3%, /products/{id} 28.6% 5xx, p95<3ms 유지 — 지연 없이 즉시 실패 응답하는 패턴은 로직 예외/의도적 실패 주입과 부합(DB·리소스 병목이면 지연 증가가 동반되는게 일반적)
- getDeploymentHistory: 최근 배포 없음(2026-07-14 초기 배포 1건) — 배포와 장애 시점 상관 없음, 코드 변경 기인 가능성 배제
- searchSimilarIncidents: 배포발 NPE(inc-2026-0412, distance 0.49)와는 유사도 낮고 배포 이력도 불일치하여 배제. 리소스 고갈성 인시던트들과도 패턴(즉시 5xx, 지연 정상)이 다름 — 참고용으로만 활용

**제안 조치 (suggested_actions)**:
- chaos/fault-injection 설정(ChaosInterceptor) 비활성화 여부 확인 — 데모/테스트 목적이 아니라면 즉시 해제
- 실제 운영 장애로 간주 시: 영향받는 파드/인스턴스 재시작으로 즉시 완화 시도
- /products, /products/{id} 트래픽을 정상 인스턴스로 우회하거나 일시적으로 캐시된 응답(fallback)으로 서빙
- 오토스케일 아웃으로 정상 인스턴스 비율을 높여 에러율 희석 (근본 해결은 아님, 완화용)
- ChaosInterceptor 관련 설정(주입 룰) 대상 엔드포인트·비율 확인 및 즉시 롤백/비활성화
- 알림 임계 초과 지속 시 온콜 에스컬레이션 및 /products 계열 API 헬스체크 강화

## 3. 조치 계획 (action 노드)

- actions: CIRCUIT_BREAK, RESTART_APP, NOTIFY_ONLY
- rationale: 근본 원인은 ChaosInterceptor에 의한 의도적 fault-injection(에러율 강제 주입)으로 판단됨(확신도 0.93). 배포 이력 없음(최근 배포 2026-07-14, 약 2개월 전)으로 배포/코드 변경 기인 가능성은 배제되었고, p95<3ms로 지연 없이 즉시 5xx가 반환되는 패턴은 리소스 고갈이나 DB 병목이 아닌 로직상 강제 실패 주입과 부합함. 이는 코드 결함(누수 등)이 아니라 설정성 주입이므로, RESTART_APP/SCALE_OUT 같은 재시작·증설 조치는 근본 해결이 아니라 인스턴스 교체를 통한 임시 완화 효과만 기대할 수 있음(주입 설정이 파드 레벨에 유지되면 재시작 후에도 재발 가능). 따라서 1순위로 CIRCUIT_BREAK를 제안하여 영향 엔드포인트(/products, /products/{id})의 트래픽을 즉시 차단해 에러 확산을 막고, 보조적으로 RESTART_APP을 통해 chaos 설정이 파드 재기동 시 초기화되는지 확인하는 완화 시도를 제안한다(단, chaos 설정이 외부 컨피그로 유지되는 구조라면 재시작은 효과 없을 수 있음 — 사람이 확인 필요). SCALE_OUT은 근본 원인이 코드/설정성 주입이라 에러율 희석 효과는 제한적이고 비용 대비 실익이 낮아 제외했다. 근거 로그의 'chaos error-rate fault 로 500 반환' 메시지는 로거명 자체가 chaos 모듈임이 명시되어 주입 의심 문구가 아닌 사실 기록으로 판단했다. 모든 상태 변경 조치는 사람의 승인이 필요하며, NOTIFY_ONLY로 온콜 담당자에게 chaos 설정 확인 및 비활성화 필요성을 즉시 알린다.
- risk: CIRCUIT_BREAK: /products 계열 기능이 완전히 사용 불가 상태가 되어 정상 요청도 차단됨 — 상품 조회 관련 사용자 기능 중단으로 인한 비즈니스 영향 발생 가능. RESTART_APP: 재시작 도중 진행 중이던 요청 유실 가능성이 있으며, chaos 설정이 외부 컨피그(예: ConfigMap, 원격 설정 서버)로 유지되는 구조라면 재시작 후에도 즉시 재발하여 효과 없이 서비스 중단 시간만 추가로 발생할 수 있음. 두 조치 모두 근본 원인(주입 설정 자체)을 제거하지 않으므로 설정을 직접 비활성화하기 전까지는 임시 완화에 그침. NOTIFY_ONLY 자체는 상태를 바꾸지 않아 리스크 없음.
- 승인: rejected · 회복: skipped

## 4. 재조회 근거 (evaluation 이 독립 조회한 실측)

시간창: 2026-09-10T04:28:32Z ~ 2026-09-10T04:38:32Z (발화 5분 전 ~ 종결, 끝 상한 = 발화 + 5분)

Prometheus (30s step):
- 5xx 비율 `sum(rate 5xx) / sum(rate all)`:
  - (합계): min 0.0 · max 0.4783 · 마지막 0.0 (17점)
- status 별 요청률:
  - status=200: min 1.3091 · max 2.5092 · 마지막 2.5091 (21점)
  - status=500: min 0.0 · max 1.2 · 마지막 0.0 (17점)
- p95 (초):
  - (합계): min 0.0039 · max 0.0056 · 마지막 0.004 (21점)
- heap 사용 비율:
  - (합계): min 0.0713 · max 0.1003 · 마지막 0.0713 (21점)

Loki `{service="target-app"}` ERROR 50건 · WARN 0건 (창 안 50줄 상한)
  - [ERROR] 2026-09-10T04:30:07 chaos error-rate fault 로 500 반환: GET /products
  - [ERROR] 2026-09-10T04:30:07 chaos error-rate fault 로 500 반환: GET /products
  - [ERROR] 2026-09-10T04:30:08 chaos error-rate fault 로 500 반환: GET /products
  - [ERROR] 2026-09-10T04:30:08 chaos error-rate fault 로 500 반환: GET /products
  - [ERROR] 2026-09-10T04:30:09 chaos error-rate fault 로 500 반환: GET /products

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
note: 가설·근거(ChaosInterceptor 500 로그 50건, 5xx 39~48%, p95 정상, 배포 없음)가 재조회와 일치. 조치는 RESTART 가 chaos 초기화라 맞으나 1순위 CIRCUIT_BREAK 는 50% 실패 상황에서 정상 요청까지 차단하는 과잉 — 사소한 부정확. 5xx 약 48% 는 P1 경계, P2 타당 (9/2 케이스와 같은 기준) [초안 claude-fable-5.1, 검수 완료 2026-09-10]
```
