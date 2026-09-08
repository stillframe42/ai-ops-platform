# ADR-0013: K8s 이전과 compose 의 역할 분담

- 상태: 승인됨
- 날짜: 2026-08-13

## 맥락

이전까지의 스택은 docker compose 단일 진입점(ADR-0001)으로 운영됐다. 이번 작업에서 로컬
K8s(kind)로 이전하며 두 가지를 결정해야 했다: ① 이전의 형태 — 무엇을 어떤 단위로 옮기는가
② 이전 후 compose 의 신분 — README 로드맵이 "compose 와의 역할 분담은 ADR 로 결정"으로
예고한 항목. 제약 조건: 비목표 "실 사용자 트래픽 없음" (Ingress/TLS·실 클라우드 제외, kind
로컬 한정), Durable Execution(ADR-0009)·무유실 파이프라인(ADR-0011)의 동작 등가 유지,
확인 기준 "빈 클러스터에서 helm install 만으로 전체 복원".

## 결정

**K8s 를 운영 형상의 표준으로 이전하고, compose 는 개발용으로 유지한다.** 이전 형태는
처음부터 Helm 차트 — 자작 6종(`charts/` — 앱 4 + postgres/kafka 자체 StatefulSet)과 외부
3종(kube-prometheus-stack·Loki·Alloy)을 umbrella(`charts/aiops`)가 dependencies 로 묶어
진입점 하나(`helm install aiops`)를 유지한다 (ADR-0001 의 include 병합과 같은 원칙의 K8s 판).
namespace 는 aiops 단일, K8s Service 명 = compose 컨테이너명으로 맞춰 **docker 프로파일을
무수정 재사용**한다 (k8s 프로파일 신설 없음 — 주소 계약의 이전). 역할 경계: **E2E 검증·
스케일링 실험·데모의 표준은 K8s**, compose 는 단일 컴포넌트의 빠른 개발 반복(빌드→up)용.

## 검토한 대안

| 대안 | 장점 | 단점 | 기각 사유 |
|------|------|------|-----------|
| raw manifest 로 이전 후 차트화 | 학습 단계가 완만 | 같은 리소스 2회 작성 (kubectl 판 → Helm 판) | 이중 작업 — 템플릿화 최소의 차트로 시작하면 완만함도 확보 (주간 순서 검토 ②) |
| compose 즉시 폐기 | 산출물 사본(대시보드 JSON·k6 스크립트) 동기 의무 소멸, 단일 형상 | 개발 반복 루프가 느려짐 (helm upgrade + kind load), README 로컬 가이드 전면 재작성 | docker 프로파일 공유로 유지 비용이 낮고, 개발 루프 가치가 아직 큼 — 폐기 재검토는 보안 작업 이후 |
| bitnami 기성 차트 (postgres·kafka) | 검증된 운영 부속 (복제·백업·메트릭) | 2025-08 무료 이미지 카탈로그 이관(업데이트 중단), chart 가 자사 이미지 레이아웃 전제라 pgvector 등 이미지 교체가 불성립 | 공급·구조 양면 리스크 — compose 검증 이미지 재사용 자체 차트가 단순·등가 (2026-08-10 선행 결정 ④) |
| namespace 2분할 유지 (aiops/monitoring) | 관심사 격리 | 한 릴리스 = 기본 한 namespace — umbrella 를 쪼개거나 별도 설치 절차 필요, 교차 ns FQDN 관리 | 확인 기준("helm install 만으로 복원")과 충돌, kind 로컬 데모에서 격리 실익 낮음 |

## 결과

- 쉬워지는 것: 빈 클러스터 → 전체 복원이 1명령 (8/13 실측: kind 재생성 포함 3분 24초 +
  E2E 스모크 완주). pod 소모품 전제와 체크포인터의 결합으로 무유실 재기동 실증 (2026-08-13 —
  분석 중 pod 삭제 → 33초 재개). 롤링·스케일링(HPA/KEDA) 실험 기반 확보.
- 어려워지는 것: 산출물 사본 2곳 동기 의무 (Grafana 대시보드 JSON·k6 스크립트 — compose
  원본과 차트 사본, compose 폐기 시 소멸). 임시 관례 2건이 부채로 남음 — Secret 반입
  스크립트(보안 주간 재검토)·default SA 무권한(RESTART_APP 자동화 보류, ADR-0005 추가 사항).
- 되돌리려면: compose 는 그대로 있으므로 `docker compose up` 복귀는 즉시 가능. charts/ 를
  버리면 K8s 산출물만 소멸 — 앱 코드·프로파일은 양쪽 공용이라 영향 없음.
- 재검토 조건: compose 폐기 여부는 보안 작업 이후 — 사본 동기 비용과 개발 루프
  가치의 재평가로 결정.

## 추가 사항 (2026-09-02): compose 폐기 재검토 — 유지 결정

재검토 조건(보안 작업 이후) 도달로 검토했다. 결론은 **유지** — 폐기하지 않는다.

- 사본 동기 비용의 실측: 보안 주간 6일 동안 Grafana 대시보드 JSON·k6 스크립트의 compose↔차트 사본 갱신은 가드레일 패널 추가 1건뿐이었고, docker 프로파일 공유(앱 이미지·설정 동일)로 서비스 정의는 사본 부담이 없었다. 예상보다 동기 의무가 가볍다.
- 개발 루프 가치는 지속: 단위 테스트는 CI(GitHub Actions, 2026-09-02 신설)가 담당하지만, 레드팀 E2E 러너·파이프라인 실측은 여전히 실행 스택이 필요하고 kind(helm upgrade + kind load)보다 compose 반복이 빠르다.
- 되돌리기 부담: 폐기는 즉시 되돌릴 수 없다(README 로컬 가이드 재작성·compose 파일 삭제). 유지의 한계 비용이 낮고 폐기의 이득(단일 형상)이 데모 규모에서 작다.

다음 재검토 조건: 사본 동기가 반복 부채로 커지거나(대시보드·부하 스크립트 대량 변경), 2026-09 Observability 작업에서 관측 스택 구성이 두 형상 간 갈라질 때.

## 추가 사항 (2026-09-08): 관측 스택도 두 형상 동시 반영 — 재검토 조건 미도달

관측 표준화([ADR-0018](0018-observability-vendor-neutral.md))에서 OTel Collector·Tempo 를 **compose(`compose.monitoring.yml`)와 umbrella 차트(`opentelemetry-collector`·`tempo` 외부 의존 pin) 양쪽에 같은 파이프라인**으로 넣었다. 두 형상의 차이는 Langfuse exporter 유무 하나(compose 전용 백엔드 — K8s 미반입 승계)이고, 이 차이가 "백엔드 교체 = Collector 설정 변경"의 실증이기도 하다. 따라서 위 재검토 조건("관측 스택이 두 형상 간 갈라질 때")에는 도달하지 않았고 **compose 유지 결정은 그대로**다.

사본 동기 의무는 늘었다: Grafana 대시보드 4종(`genai-observability` 추가)·Collector 설정(`infra/otel/collector.yml` ↔ values `alternateConfig`)·Tempo 설정(`infra/tempo/tempo.yml` ↔ values `tempo.*`)·Prometheus 스크레이프(compose 잡 ↔ ServiceMonitor). compose 의 llm-gateway 스크레이프 잡이 빠져 있던 드리프트(게이트웨이 대시보드가 compose 에서 비어 있었음)와 차트 대시보드 사본의 패널 누락 1건을 2026-09-08 에 발견·정정했다 — 사본 동기는 "변경 시" 뿐 아니라 실측 시점에 대조가 필요하다.
