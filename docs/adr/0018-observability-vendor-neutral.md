# ADR-0018: 관측성 벤더 중립 전략 — 표준 우선 계측 + Collector 중심 파이프라인

- 상태: 승인됨 (2026-09-03 초안 → 2026-09-08 확정)
- 날짜: 2026-09-03

## 맥락

재계측 전(2026-09-03 인벤토리) LLM 관측은 두 갈래였다. LLM 호출·세션·비용은 Langfuse `CallbackHandler`(agent-service `tracing.py`·`runtime.py`)가 벤더 SDK 규약으로 기록했고, 트레이스는 OTel TracerProvider + httpx 계측 + 수동 스팬 2종(`incident.run`·`incident.resume`)이 만들되 **exporter 가 어디에도 없어 전송이 0** 이었다 (JVM 도 Micrometer→OTel 브리지만 장착). MCP 도구 계측은 Micrometer 타이머 `mcp.tool.calls`, 스팬 이름·속성은 임의였다. 즉 계측 어휘가 벤더 SDK 와 자체 이름에 갈라져 있어, 백엔드를 바꾸면 앱 코드가 따라 바뀌는 구조였다.

이 결정을 촉발한 외부 변화는 두 가지다 (plan 검토 시 인용, `notes/qna/20260903` — 인수 사실 자체는 이 ADR 의 근거가 아니라 계기). ① 대표 LLM 관측 벤더의 인수(Langfuse → ClickHouse, Galileo → Cisco)로 "벤더 SDK 로 계측하면 백엔드 회사의 사정(가격·셀프호스팅 정책·SDK 유지)에 앱 코드가 종속된다"는 구조 문제가 드러났다. ② OTel GenAI 시맨틱 컨벤션(`gen_ai.*` — Client/Agent/MCP Spans·Events·Metrics)이 전용 리포로 분리돼 아직 **Development 상태**(이름 변경 가능, 공식 릴리즈 없음)이지만, 수신 측(Grafana·Datadog·Langfuse 3.x 의 OTLP 수신)이 먼저 이 어휘를 채택해 사용 측 합의는 사실상 끝났다.

제약: 두 배포 형상(compose·K8s)에 동시에 반영해야 한다 — [ADR-0013](0013-k8s-migration.md)의 compose 재검토 조건이 "관측 스택이 두 형상 간 갈라질 때"라서, K8s 에만 넣으면 그 조건에 도달한다. Langfuse 는 K8s 형상에 배포하지 않는다(승계). 실사용자 트래픽은 없다(비목표).

## 결정

**앱은 OTel GenAI 표준 어휘로만 계측하고, 모든 시그널을 전용 OTel Collector 하나로 보낸다. 백엔드는 Collector exporter 설정이 결정한다.** Langfuse 는 백엔드 중 하나(compose 전용)로 내려가고, 벤더 SDK(Langfuse 콜백)는 제거한다. 표준이 다루지 않는 것은 자체 네임스페이스(`gateway.*`·`aiops.*`·`incident.id`)에 두며 표준 네임스페이스(`gen_ai.*`·`mcp.*`)에 임의 키를 추가하지 않는다. 세부 매핑·규칙·버전 pin 은 [`docs/otel-genai-mapping.md`](../otel-genai-mapping.md) 가 단일 원천이다.

세부 결정 (날짜는 실측 확정일):

| 항목 | 결정 | 근거·실측 |
|---|---|---|
| Collector 실체 | 전용 `otel-collector-contrib` (compose 서비스 + umbrella 차트 의존) | 표준 YAML·OTTL 재현성, "벤더 배포판 아님" (2026-09-03 사용자 확정). Alloy 는 로그 수송으로 한정 ([ADR-0004](0004-loki-adoption.md) 추가 사항) |
| Python 계측 | contrib `openai-v2`(Client Spans·Metrics) + `util-genai` 핸들러(`invoke_agent`·`execute_tool`), 워크플로 루트만 raw span | 공식 유틸이 컨벤션과 함께 이동. 루트는 span link(승인 전후)·시작 시점 속성이 필요해 raw (2026-09-07) |
| JVM 계측 | Spring AI ChatModel 관측(자동) + MCP 서버 스팬 직접(`McpToolMetrics`, OTel API) + Kafka observation + `@Async` 컨텍스트 전파 + Security 관측은 인증만 | Spring AI 2.0.0 MCP 모듈에 관측 없음(실측). Security 요청 체인 스팬은 요청당 4~5 개 노이즈 → 원천 축소 (2026-09-08) |
| 세션 축 | `gen_ai.conversation.id` = incident id (+ `incident.id` 검색 축), 루트 부여 후 SpanProcessor 가 하위 상속 | Langfuse 가 표준 이름을 세션으로 인식(2026-09-03 실측) — 벤더 전용 이름 불사용 |
| 파이프라인 | receivers otlp / processors memory_limiter·transform(`normalize`·`strip-content`)·filter(`agent-only`)·batch / exporters otlp→Tempo·prometheus→Prometheus 스크레이프·otlphttp→Langfuse(compose 만) | 두 형상의 차이 = Langfuse exporter 하나. 메트릭은 pull 모델 유지 (remote write 미설정) |
| 세대 차이 흡수 | Spring AI 의 `gen_ai.system`→`gen_ai.provider.name` 복제, `finish_reasons` 문자열→배열 (Collector transform) | 앱 코드가 아닌 파이프라인 규칙 — 라이브러리 세대가 바뀌면 규칙만 제거 |
| 콘텐츠 | 로컬 `SPAN_ONLY`(Langfuse 표시용) / 운영 미설정(`NO_CONTENT`), Tempo 경로는 Collector 가 삭제 | 캡처 지점이 게이트웨이 마스킹보다 앞이라 원문 — 운영 형상 밖으로 내보내지 않는다 |
| 대시보드 | 표준 어휘 패널은 `genai-observability` 로 분리, `gateway.*`·`mcp.tool.calls` 패널은 유지 | "표준이 커버 vs 우리 확장" 경계를 대시보드 단위로 유지 (2026-09-08) |
| 버전 고정 | SDK·계측기·semconv·Spring AI·Langfuse 를 pin, 옵트인 `gen_ai_latest_experimental` 을 코드에서 고정, 버전 업 시 속성 diff 절차 | 컨벤션이 이동 중 — mapping §1·§7 |

## 검토한 대안

| 대안 | 장점 | 단점 | 기각 사유 |
|------|------|------|-----------|
| Alloy 를 OTel collector 로 재사용 (ADR-0004 예고) | 컴포넌트 +0, 두 형상에 이미 배포 | Alloy 설정 문법(River)·Grafana 배포판 인상, 현행 Alloy 는 로그 전용 34줄이라 재사용 절감이 작음 | 전용 collector 의 표준 YAML·OTTL 이 ADR·재현성에 유리 — 2026-09-03 사용자 확정 |
| Langfuse 콜백 유지 + OTel 스팬 병행 | 세션·비용 UI 즉시, 전환 위험 낮음 | 같은 호출이 두 번 기록, 계측이 벤더 SDK 에 잔존, K8s 에는 콜백 대상이 없음 | Langfuse 3.222.0 이 OTLP 를 받고 `gen_ai.conversation.id`·`gen_ai.response.model` 로 세션·비용을 표시함을 실측 → 콜백 없이 같은 UI (비용 0.044 USD 표시 유지, 2026-09-07) |
| `opentelemetry-instrumentation-langchain` | LangChain/LangGraph 계층 자동 계측 | Traceloop OpenLLMetry 패키지 — `semantic-conventions-ai` 의존, 공식 컨벤션 아님 | 어휘 표준성 위배 |
| Collector 없이 앱 → Tempo·Langfuse 직접 전송 | 컴포넌트 −1 | 앱이 백엔드 수만큼 exporter 를 갖고, 콘텐츠 삭제·필터 규칙을 앱마다 구현 | "백엔드 교체 = 설정 변경" 이 성립하지 않음 |
| 벤더 네이티브 에이전트·SDK (LLM 전용 APM) | 대시보드 자동 생성 | 인수와 같은 종속을 재생산, 비용 | 벤더 중립 목적 자체와 충돌 |

## 결과

- 쉬워지는 것 — 실증 3건: ① **백엔드 교체 = Collector 설정 변경** — Langfuse exporter off/on 4회에 앱 컨테이너 재시작 0 (2026-09-03) ② **인시던트 1건이 하나의 traceId** — 웹훅(control-plane) → Kafka → 워크플로 → 에이전트 노드 → MCP 도구(control-plane 서버 스팬) → 게이트웨이(Spring AI) → LLM, 승인 전후는 span link, Loki 감사 로그와 traceId 상관 (2026-09-07) ③ **표준 메트릭으로 대시보드** — `gen_ai.client.token.usage`·`operation.duration` 이 두 형상에서 Prometheus 도달, TraceQL 메트릭으로 도구·노드 지연 (2026-09-08). 표준 어휘라 Tempo·Langfuse·Grafana 어디서든 같은 질의(`gen_ai.request.model`·`gen_ai.usage.*`)가 된다.
- 어려워지는 것: 컴포넌트 +2(Collector·Tempo)와 두 형상 사본 동기(Collector·Tempo 설정, 대시보드 4종) / 같은 LLM 호출이 두 gen_ai 스팬(클라이언트·게이트웨이)이라 Langfuse 는 filter 로 agent-service 만 받는다 / 클라이언트 스팬의 `gen_ai.request.model` 은 게이트웨이 별칭 `default`(실모델은 `gen_ai.response.model`), `gen_ai.provider.name=openai` 는 규격상 프록시 경유 허용값 — 실프로바이더 축은 게이트웨이 스팬·메트릭 / 실험 컨벤션 pin — 버전 업마다 속성 diff / 캡처 본문은 마스킹 전 원문 — 캡처를 켠 환경의 Langfuse 는 신뢰 경계 안 저장소로 취급 ([위협 모델](../security/threat-model.md) §5).
- 되돌리기: 앱은 Collector 주소만 알므로 Collector 를 남긴 채 exporter 만 바꾸면 된다. Langfuse 를 다시 주 백엔드로 삼더라도 exporter 재활성이면 되고 콜백 복원은 불필요하다. 표준 어휘 자체를 되돌릴 이유는 없다.
- 재평가 조건: GenAI 컨벤션 안정 릴리즈(pin 해제·이름 이전 diff) / Langfuse 셀프호스팅 정책 변화(exporter 제거로 대응) / 두 형상 관측 스택이 갈라질 때(ADR-0013 조건) / 계측 오버헤드가 인시던트 처리 시간의 수 % 를 넘을 때(측정은 후속 작업).
