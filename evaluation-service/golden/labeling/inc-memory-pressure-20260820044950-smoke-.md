# 골든셋 라벨링 — inc-memory-pressure-20260820044950-smoke-

> 시나리오 **memory-pressure** · Alert `TargetAppHeapUsageHigh` · 발화 2026-08-20T04:49:50Z · 종결 2026-08-20T04:59:09.337299+00:00 · 상태 completed
> **주입 사실 (정답)**: POST /chaos/memory-leak?mbPerMin=100 — 분당 100MB 힙 점유 (OOM 경로)
> 판단 기준: 보고서가 그럴듯한가가 아니라 **주입 사실 + §4 재조회 수치에 맞는가**.

## 1. 모니터링 요약 (monitor 노드)

실행한 질의:
- get_active_alerts({})
- query_prometheus_range({"promql": "jvm_memory_usage_after_gc{area=\"heap\"}", "minutes": 30})
- query_prometheus({"promql": "jvm_memory_used_bytes{area=\"heap\"} / jvm_memory_max_bytes{area=\"heap\"}"})
- query_prometheus({"promql": "rate(jvm_gc_pause_seconds_count[5m])"})
- query_prometheus({"promql": "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri))"})
- query_prometheus({"promql": "sum(rate(http_server_requests_seconds_count{status=~\"5..\"}[3m])) / sum(rate(http_server_requests_seconds_count[3m]))"})

---

## **상황 요약**

`target-app` Heap 메모리 사용률이 30분 구간 내에 꾸준한 증가 추세를 보이고 있습니다. 
현재 Tenured Gen에서 약 7.0% 사용 중이며, GC 직후 heap 사용률도 시간이 지나면서 상향 이동하고 있습니다 (초기 5.4% → 현재 7.0%). 
다른 애플리케이션(control-plane, llm-gateway)과 비교하면 target-app의 heap 압박이 상대적으로 낮으나, 
지속적인 상승세는 메모리 누수 가능성을 시사하며 스모크 테스트(DAY 33) 기간 동안 모니터링 필요합니다.

## 2. 분석 보고서 (analysis 노드) — 평가 대상

**severity**: P2

**근본 원인 가설**:
target-app의 heap 사용률이 지난 1시간 동안 4.5% → 11.3%로 약 2.5배 상승했고, GC 실행 빈도도 함께 증가(같은 구간 기준 상승)했음에도 사용률이 GC 이후에도 계속 우상향하는 패턴이 관찰됨. 이는 GC로 회수되지 않는 객체가 누적되는 전형적인 메모리 누수 패턴(예: TTL 미설정 캐시/컬렉션의 무한 증식)과 일치. 배포 이력상 최근 배포가 없어(최종 배포 2026-07-14, 장애와 5주 이상 격차) 코드 변경이 직접 원인일 가능성은 낮고, 데모 앱의 fault-injection(chaos) 엔드포인트 특성상 메모리 누수 상황이 인위적으로 주입되었을 가능성이 높음. 다만 실제 운영 환경에서 동일 패턴이 발생했다면 누수 객체가 Tenured Gen에 누적되어 궁극적으로 OOM 및 서비스 중단으로 이어질 수 있는 심각한 이슈로 취급해야 함.

**근거 (evidence)**:
- compare_with_baseline(heap used/max 비율): 1시간 전 4.5% → 현재 11.3%, 약 2.5배 상승
- compare_with_baseline(GC pause count rate): 1시간 전 대비 현재 GC 실행 빈도 증가 — GC가 더 자주 도는데도 heap 사용률이 계속 상승 = 회수 안 되는 메모리 누적 정황
- 모니터링 요약: Tenured Gen 사용률이 30분간 5.4% → 7.0%로 꾸준히 우상향, GC 직후 baseline도 동반 상승
- get_app_logs(WARN, 30분): 관련 경고/에러 로그 없음 — 명시적 예외 기반 누수보다는 정상 흐름 내 객체 누적 가능성
- getDeploymentHistory: 최근 배포 없음(2026-07-14 초기 배포 이후 변경 無) — 최근 코드 변경과의 상관관계 배제
- searchSimilarIncidents: inc-2026-0619 (유사도 상위) — TTL 미설정 로컬 캐시의 무한 증식으로 30분간 힙 우상향 후 OOM 근접, RESTART_APP으로 완화 + 캐시 TTL 설정으로 재발 방지한 선례와 패턴 일치
- getAppConfig: target-app은 fault-injection(chaos) 엔드포인트를 가진 데모 앱이며 재기동 시 chaos 상태 초기화됨 — 인위적 주입 가능성을 시사하나 증상 자체는 실제 메모리 누수와 동일하게 분석·조치해야 함
- replicas=1 단일 컨테이너 구성 — 누수 지속 시 즉시 단일 장애점(SPOF)으로 이어질 위험

**제안 조치 (suggested_actions)**:
- 단기: target-app 재기동(RESTART_APP)으로 heap 상태 초기화 및 즉시 완화
- heap dump(jmap/actuator heapdump)를 재기동 전에 확보해 어떤 객체가 누적되는지 식별
- 코드 레벨에서 TTL·최대 크기 미설정 캐시, 무한 증식 컬렉션(static Map/List 등) 여부 점검
- JVM heap/GC 모니터링 알림 임계값 재점검 및 지속 관찰(스모크 테스트 DAY 33 종료까지 추이 추적)
- 재발 방지: 캐시 사용 시 TTL 및 최대 크기(예: Caffeine maximumSize) 설정 강제화
- replicas=1 단일 인스턴스 구조 개선 검토 — 최소 2 replica로 확장해 단일 인스턴스 메모리 문제의 서비스 영향 완화

## 3. 조치 계획 (action 노드)

- actions: RESTART_APP, NOTIFY_ONLY
- rationale: 근본 원인 가설은 TTL 미설정 캐시/컬렉션 등 코드 결함에 의한 메모리 누수로 판단됨(GC 빈도 증가에도 heap 및 Tenured Gen 사용률이 지속 우상향, 유사 인시던트 inc-2026-0619와 패턴 일치). RESTART_APP은 heap 상태를 초기화해 증상을 즉시 완화하는 임시 조치일 뿐, 누수의 근본 원인(TTL/최대 크기 미설정 캐시, 무한 증식 컬렉션 등)을 해결하지 못하므로 반드시 코드 레벨 조치(캐시 TTL/maxSize 설정)가 후속되어야 함. 현재 heap 사용률이 11.3% 수준으로 OOM 임박 상황은 아니며(P2, 확신도 0.55) SCALE_OUT이나 CIRCUIT_BREAK 같은 추가 자원/기능 제한 조치는 과잉 대응으로 판단해 제외. 최근 배포 이력이 없어 ROLLBACK도 해당 사항 없음. NOTIFY_ONLY는 담당자에게 heap dump 확보(재기동 전) 및 코드 레벨 캐시/컬렉션 점검, replicas=1 SPOF 구조 개선 검토를 요청하기 위해 병행 제안. 데모 환경의 fault-injection 특성상 재기동으로 chaos 상태가 초기화될 수 있으나, 동일 증상이 실제 운영에서 발생했다면 취할 조치와 동일하게 제안함(환경 특성과 무관하게 실제 장애 대응 기준 적용).
- risk: RESTART_APP: 재시작 중 짧은 다운타임 및 처리 중이던 요청 유실 가능성 존재(현재 replicas=1이라 SPOF로 인해 순간적 서비스 중단 발생 가능); heap dump를 재기동 전에 확보하지 않으면 원인 객체 식별 기회를 상실함; 데모 앱 특성상 재기동 시 chaos 상태가 초기화되어 fault-injection 시나리오 자체가 리셋될 수 있음(운영 환경에서는 해당 없음). 근본 원인(캐시 TTL 미설정 등)이 해결되지 않으면 재시작 후에도 동일한 heap 우상향 패턴이 재발할 가능성이 높음 — 임시 완화일 뿐임을 유의.
- 승인: approved · 회복: recovered

## 4. 재조회 근거 (evaluation 이 독립 조회한 실측)

재조회 불가 — 보존 기간 밖 (Prometheus 10d · Loki 2026-08-28 이후). 보고서 내부 정합과 주입 사실만으로 판정한다.

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
faithfulness: 0.7
actionability: 1.0
severity_accuracy: 1.0
failure_mode: 없음      # A | B | C | D | 없음
note: 재조회 불가(보존 밖). 누수 패턴(GC 증가에도 heap 우상향·Tenured 상승·유사 사례 0619) + chaos 주입 가능성 = 주입 사실과 일치, 승인 후 recovered 로 사후 확인. 부정확 의심: heap 11.3% 는 Alert 임계(75%) 와 어긋남 — 지표 식 차이 또는 리셋 후 조회 가능성, 사용자 확인 요망. RESTART_APP + 재기동 전 heap dump + TTL 캐시 점검은 구체·카탈로그 안, 단일 레플리카 OOM 경로라 P2 타당 [초안 claude-fable-5.1, 검수 완료 2026-09-09]
```
