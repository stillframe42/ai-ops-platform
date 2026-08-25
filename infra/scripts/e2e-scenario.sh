#!/usr/bin/env bash
# E2E 시나리오 반복 드라이버 (DAY 20) — 주입→발화→Slack 도착 시각을 기계 기록하고,
# reset 후 정착 대기(Alert 해소 + 최장 rate 창 5m 경과)까지 한 사이클로 절차화.
#
# 사용: e2e-scenario.sh <latency|error-rate|memory-leak>   # 1사이클 (주입→기록→reset→정착)
#       e2e-scenario.sh settle                              # 정착 대기만 (수동 개입 후 복구용)
# 기록: $RESULTS_FILE (기본 ./e2e-runs.jsonl) 에 회차당 JSON 한 줄 추가
set -euo pipefail

TARGET=${TARGET:-http://localhost:8080}
CP=${CP:-http://localhost:8081}
AM=${AM:-http://localhost:9093}
SETTLE_SECONDS=${SETTLE_SECONDS:-360}   # 최장 rate 창 5m + 여유 — reset 시점 기준
FIRE_TIMEOUT=${FIRE_TIMEOUT:-900}
PIPELINE_TIMEOUT=${PIPELINE_TIMEOUT:-240}
RESULTS_FILE=${RESULTS_FILE:-./e2e-runs.jsonl}
COMPOSE_DIR="$(cd "$(dirname "$0")/.." && pwd)"

log() { echo "[$(date -u +%H:%M:%S)] $*" >&2; }
now_iso() { python3 -c 'from datetime import datetime,timezone; print(datetime.now(timezone.utc).isoformat())'; }

# scenario 라벨이 있는 활성 Alert 수 (target-app 룰 전부 해당)
active_alerts() {
  curl -s "$AM/api/v2/alerts?active=true" | jq '[.[] | select(.labels.scenario != null)] | length'
}

# reset 시점부터 Alert 전부 해소 + SETTLE_SECONDS 경과까지 대기
settle() {
  local since=$(date +%s)
  log "정착 대기 — Alert 해소 + ${SETTLE_SECONDS}s (rate 창 소진)"
  while [ "$(active_alerts)" != "0" ]; do sleep 10; done
  local remain=$(( SETTLE_SECONDS - ( $(date +%s) - since ) ))
  [ "$remain" -gt 0 ] && sleep "$remain"
  log "정착 완료"
}

inject() {
  case "$1" in
    latency)      curl -sf --max-time 10 -X POST "$TARGET/chaos/latency?ms=3500&percent=100" > /dev/null ;;
    error-rate)   curl -sf --max-time 10 -X POST "$TARGET/chaos/error-rate?percent=50" > /dev/null ;;
    memory-leak)  curl -sf --max-time 10 -X POST "$TARGET/chaos/memory-leak?mbPerMin=100" > /dev/null ;;
  esac
}

# reset 실패(OOM 등 무응답) 시 재기동 복구 — chaos 상태는 인메모리라 재시작 = 전체 해제
reset_or_recover() {
  if ! curl -sf --max-time 10 -X POST "$TARGET/chaos/reset" > /dev/null; then
    log "reset 무응답 — target-app 재기동으로 복구 (메모리 누수 OOM 경로)"
    (cd "$COMPOSE_DIR" && docker compose restart target-app) > /dev/null 2>&1
    until curl -sf --max-time 3 "$TARGET/chaos" > /dev/null 2>&1; do sleep 3; done
    log "target-app 회복"
  fi
}

alertname_of() {
  case "$1" in
    latency) echo TargetAppHighLatency ;;
    error-rate) echo TargetAppHighErrorRate ;;
    memory-leak) echo TargetAppHeapUsageHigh ;;
  esac
}

scenario_label_of() {
  case "$1" in
    latency) echo latency-surge ;;
    error-rate) echo error-rate-surge ;;
    memory-leak) echo memory-pressure ;;
  esac
}

# 발화 대기 — Alertmanager 의 startsAt(발화 시각)을 반환
wait_fire() {
  local alertname=$1 deadline=$(( $(date +%s) + FIRE_TIMEOUT ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    local starts
    starts=$(curl -s "$AM/api/v2/alerts?active=true" \
      | jq -r ".[] | select(.labels.alertname==\"$alertname\") | .startsAt" | head -1)
    if [ -n "$starts" ]; then echo "$starts"; return 0; fi
    sleep 5
  done
  return 1
}

# 신규 인시던트 행 대기 — 주입 전 baseline 에 없던 id 를 반환
wait_incident() {
  local scenario_label=$1 baseline=$2 deadline=$(( $(date +%s) + PIPELINE_TIMEOUT ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    local id
    id=$(curl -s "$CP/api/incidents?limit=5" \
      | jq -r ".[] | select(.scenario==\"$scenario_label\") | .incident_id" \
      | grep -vxF -f "$baseline" | head -1 || true)
    if [ -n "$id" ]; then echo "$id"; return 0; fi
    sleep 3
  done
  return 1
}

# Slack 발송 로그 시각 (없으면 빈 값 — 비활성/실패 구분은 로그로)
slack_sent_at() {
  local id=$1 since=$2
  (cd "$COMPOSE_DIR" && docker compose logs control-plane --since "$since" 2>/dev/null) \
    | grep "Slack 알림 발송 — $id" | sed 's/^[^|]*| //' | jq -r '."@timestamp"' | head -1
}

run_cycle() {
  local scenario=$1
  local alertname scenario_label
  alertname=$(alertname_of "$scenario")
  scenario_label=$(scenario_label_of "$scenario")

  # 전제: 활성 Alert 0 + chaos 클린 (아니면 reset 후 정착부터)
  if [ "$(active_alerts)" != "0" ]; then
    log "활성 Alert 잔존 — reset 후 정착 대기부터"
    reset_or_recover
    settle
  fi

  local baseline
  baseline=$(mktemp)
  curl -s "$CP/api/incidents?limit=20" | jq -r '.[].incident_id' > "$baseline"

  local t_inject t_fire incident_id t_slack="" outcome=ok
  t_inject=$(now_iso)
  inject "$scenario"
  log "$scenario 주입 완료 ($t_inject) — 발화 대기 (최대 ${FIRE_TIMEOUT}s)"

  if ! t_fire=$(wait_fire "$alertname"); then
    outcome=fire_timeout
    log "발화 시간 초과"
  else
    log "발화 $t_fire — 파이프라인 대기"
    if incident_id=$(wait_incident "$scenario_label" "$baseline"); then
      sleep 3   # Slack 발송 로그 플러시 여유
      t_slack=$(slack_sent_at "$incident_id" "$t_inject")
      log "저장·발송 확인 — $incident_id (slack: ${t_slack:-미발송})"
    else
      outcome=pipeline_timeout
      log "파이프라인 시간 초과"
    fi
  fi

  reset_or_recover
  log "reset 완료"

  # 회차 기록 — 구간 시간 계산 + API 요약 필드 결합
  local summary=null
  [ -n "${incident_id:-}" ] && summary=$(curl -s "$CP/api/incidents?limit=5" \
    | jq -c ".[] | select(.incident_id==\"$incident_id\") | {status, severity, confidence}")
  python3 - "$scenario" "$outcome" "$t_inject" "${t_fire:-}" "${t_slack:-}" "${incident_id:-}" "$summary" >> "$RESULTS_FILE" <<'PY'
import json, sys
from datetime import datetime
scenario, outcome, t_inject, t_fire, t_slack, incident_id, summary = sys.argv[1:8]
def ts(s): return datetime.fromisoformat(s.replace("Z", "+00:00")) if s else None
i, f, k = ts(t_inject), ts(t_fire), ts(t_slack)
print(json.dumps({
    "scenario": scenario, "outcome": outcome, "incident_id": incident_id or None,
    "t_inject": t_inject, "t_fire": t_fire or None, "t_slack": t_slack or None,
    "fire_wait_s": round((f - i).total_seconds(), 1) if f else None,
    "pipeline_s": round((k - f).total_seconds(), 1) if k and f else None,
    "summary": json.loads(summary) if summary and summary != "null" else None,
}, ensure_ascii=False))
PY
  rm -f "$baseline"
  settle
}

case "${1:-}" in
  latency|error-rate|memory-leak) run_cycle "$1" ;;
  settle) settle ;;
  *) echo "사용: $0 <latency|error-rate|memory-leak|settle>" >&2; exit 1 ;;
esac
