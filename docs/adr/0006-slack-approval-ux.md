# ADR-0006: Slack 승인 UX — Socket Mode + Block Kit 버튼

- 상태: 승인됨
- 날짜: 2026-07-31

## 맥락

초기 Slack 연동은 Incoming Webhook 발신 전용(분석 보고 알림)이었다. human-in-the-loop 는 사람의 승인 입력을 받아야 하는데, Incoming Webhook 으로는 버튼 클릭을 수신할 수 없다 (scenarios.md ADR-0006 예약). 표준 Interactivity 는 Slack 이 호출할 공개 Request URL 을 요구하지만, 로컬 compose 스택에는 공개 URL 이 없다.

DAY 19 의 알림 포맷(`SlackNotifier.buildMessage`)이 승인 요청 포맷의 초안이다 (코드 주석으로 예약됨).

## 결정

**Slack App + Socket Mode 로 승인 버튼을 수신한다.**

- 메시지: Block Kit — 기존 알림 내용(시나리오·P등급·confidence·근거 요약) + 조치안(action_type·params·사유) + [승인][거부] 버튼
- 수신: Socket Mode (WebSocket outbound 연결) — 공개 URL 불요, 로컬 개발 표준. control-plane 이 Bolt for Java 로 수신
- 처리 수렴: 버튼 클릭 핸들러는 승인 API 와 같은 서비스 로직을 호출한다 — 승인 처리는 한 곳, Slack 은 입력 채널 (API 는 curl/테스트 경로로 유지)
- 승인자 식별: 클릭한 Slack user ID 를 `decided_by` 감사 컬럼에 기록
- 타임아웃 정책: 30분 미승인 시 재알림 1회 → 60분(재알림 후 30분) 미승인 시 `expired` 전이 = **조치 미실행 종결** (자동 승인 아님 — 오래된 관측 기반 조치의 실행이 더 위험, 장애가 지속되면 재발화가 새 사이클을 만든다)
- 준비물 (사용자): Slack App 생성, Bot Token(`xoxb-`)·App-Level Token(`xapp-`, `connections:write`) 발급 — `infra/.env` git 미추적, 값 미출력 관례

## 검토한 대안

| 대안 | 장점 | 단점 | 기각 사유 |
|------|------|------|-----------|
| 승인 링크 방식 (Incoming Webhook 유지) | 준비물 없음 — 가장 단순 | 승인자 식별 별도 처리 필요, GET 링크로 상태 변경(멱등성·오클릭 위험), 버튼 UX 아님 | 승인자 감사가 HITL 의 핵심 산출물 — 식별 없는 승인은 반쪽 |
| 터널 (ngrok 류) + HTTP Interactivity | 실운영과 가장 유사한 형태 | 터널 상시 유지·URL 변동 관리, 로컬 스택 밖 의존 추가 | 데모 편익 대비 운영 부담 — K8s 전환(2026-08, 공개 인그레스) 때 자연 해소 |
| Socket Mode (채택) | 공개 URL 불요, Block Kit 전체 사용, 승인자 ID 수신 | Slack App 생성·토큰 2종 관리, Bolt 의존성 추가 | — |

## 결과

- 쉬워지는 것: 승인·거부가 알림과 같은 채널에서 완결, 승인자·시각이 자동 기록. Interactivity payload 에 사용자·메시지 컨텍스트가 포함돼 별도 상관관계 처리가 없다.
- 어려워지는 것: 토큰 2종의 수명 관리, Socket Mode 연결의 재연결 처리 (Bolt 기본 제공 확인 필요). Incoming Webhook 발신 경로와 App 발신(chat.postMessage) 이원화 — 승인 요청은 App 경로로 보내야 스레드·버튼 갱신이 가능하므로 알림 경로의 단계적 이관을 검토한다.
- 되돌리려면: 수신부만 교체 (승인 API·테이블·Slack 메시지 포맷은 재사용) — 링크 방식·HTTP Interactivity 로의 전환 비용은 수신 어댑터 1개.
