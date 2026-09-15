#!/usr/bin/env bash
# A/B 실험 반복 주입 드라이버 (ADR-0019 실험 층) — e2e-scenario.sh 3종을 rounds 회 순환하며 회차별 결과를 jsonl 로 남긴다.
# 배정은 agent-service 가 incident_id 해시로 하므로 이 스크립트는 variant 를 고르지 않는다 — 인시던트를 만들고 기록만 한다.
#
# 사용: experiment-run.sh <experiment> <rounds>      # 예: experiment-run.sh analysis-prompt-v2 5 (3종 × 5 = 15사이클, 회차당 6~10분)
#       RESUME=1 experiment-run.sh <experiment> <rounds>   # 진행 파일(.progress)에 기록된 다음 회차부터 재개
# 기록: $RESULTS_FILE (기본 ./experiment-<experiment>.jsonl) — 회차·시나리오·incident_id·variant·소요 시간
# 전제: compose 스택 기동 + agent-service experiments.yml 의 <experiment> 가 status: active
# AUTO_DECIDE=reject|approve|none (기본 reject): 승인 대기가 생기면 사람 대신 결정해 회차를 닫는다 — 결정이 없으면 보고서
#   발행이 만료(60m)까지 미뤄져 회차가 시간 초과로 실패한다. 실험 변인은 분석 프롬프트·모델이라 결정 방향은 채점에 무관
set -euo pipefail

EXPERIMENT=${1:?사용: $0 <experiment> <rounds>}
ROUNDS=${2:?사용: $0 <experiment> <rounds>}
CP=${CP:-http://localhost:8081}
SCENARIOS=(latency error-rate memory-leak)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESULTS_FILE=${RESULTS_FILE:-./experiment-${EXPERIMENT}.jsonl}
PROGRESS_FILE=${PROGRESS_FILE:-./experiment-${EXPERIMENT}.progress}
CYCLE_RESULTS=$(mktemp)
AUTO_DECIDE=${AUTO_DECIDE:-reject}
COMPOSE_DIR="$SCRIPT_DIR/.."

log() { echo "[$(date -u +%H:%M:%S)] 실험 $EXPERIMENT $*" >&2; }

# 승인 대기 자동 결정 — 10초마다 pending 행을 찾아 결정 API 호출 (ops:approve)
approve_token() {
  set -a; . "$SCRIPT_DIR/../.env"; set +a
  curl -s -u "ops-admin:${AUTH_CLIENT_SECRET_OPS_ADMIN}" -d "grant_type=client_credentials&scope=ops:approve ops:read" \
    "${AUTH:-http://localhost:8091}/oauth2/token" | jq -r .access_token
}

auto_decide_loop() {
  [ "$AUTO_DECIDE" = "none" ] && return 0
  while :; do
    local pending
    pending=$(cd "$COMPOSE_DIR" && docker compose exec -T postgres psql -U aiops -d controlplane -tAc \
      "select incident_id from action_approvals where status='pending'" 2>/dev/null || true)
    for id in $pending; do
      code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$CP/api/incidents/$id/$AUTO_DECIDE" \
        -H "Authorization: Bearer $(approve_token)" -H 'Content-Type: application/json' \
        -d "{\"reason\":\"실험 반복 주입 자동 결정 ($AUTO_DECIDE)\"}")
      log "승인 대기 $id → $AUTO_DECIDE (http $code)"
    done
    sleep 10
  done
}

variant_of() {
  local id=$1
  curl -s -H "Authorization: Bearer $CP_TOKEN" "$CP/api/incidents/$id" \
    | jq -r '.report.experiment | if . == null then "none" else "\(.name):\(.variant)" end'
}

start=1
if [ "${RESUME:-0}" = "1" ] && [ -f "$PROGRESS_FILE" ]; then
  start=$(( $(cat "$PROGRESS_FILE") + 1 ))
  log "재개 — 회차 $start 부터"
fi

auto_decide_loop &
DECIDER_PID=$!
trap 'kill $DECIDER_PID 2>/dev/null; rm -f "$CYCLE_RESULTS"' EXIT

total=$(( ROUNDS * ${#SCENARIOS[@]} ))
for (( cycle=start; cycle<=total; cycle++ )); do
  scenario=${SCENARIOS[$(( (cycle - 1) % ${#SCENARIOS[@]} ))]}
  round=$(( (cycle - 1) / ${#SCENARIOS[@]} + 1 ))
  log "회차 $cycle/$total (라운드 $round, $scenario)"
  # 회차마다 토큰 재발급 — 실험 1회는 수 시간이라 토큰 만료를 넘긴다 (e2e-scenario.sh 의 인시던트 조회에 필요)
  export CP_TOKEN=$(approve_token)
  t0=$(date +%s)
  : > "$CYCLE_RESULTS"
  # 실패 회차는 1회 재시도 — fire/pipeline timeout 은 정착·재주입으로 대개 회복된다
  attempt=1
  while :; do
    if RESULTS_FILE="$CYCLE_RESULTS" "$SCRIPT_DIR/e2e-scenario.sh" "$scenario"; then :; else log "e2e 스크립트 비정상 종료 (attempt $attempt)"; fi
    outcome=$(tail -1 "$CYCLE_RESULTS" 2>/dev/null | jq -r '.outcome // "no-record"')
    [ "$outcome" = "ok" ] && break
    if [ "$attempt" -ge 2 ]; then log "회차 $cycle 실패 확정 ($outcome)"; break; fi
    attempt=$(( attempt + 1 )); log "회차 $cycle 재시도 ($outcome)"
  done
  incident_id=$(tail -1 "$CYCLE_RESULTS" 2>/dev/null | jq -r '.incident_id // empty')
  variant=none
  [ -n "$incident_id" ] && variant=$(variant_of "$incident_id")
  elapsed=$(( $(date +%s) - t0 ))
  tail -1 "$CYCLE_RESULTS" 2>/dev/null \
    | jq -c --arg exp "$EXPERIMENT" --argjson cycle "$cycle" --argjson round "$round" --arg variant "$variant" \
        --argjson attempts "$attempt" --argjson elapsed "$elapsed" \
        '. + {experiment: $exp, cycle: $cycle, round: $round, variant: $variant, attempts: $attempts, cycle_s: $elapsed}' \
    >> "$RESULTS_FILE"
  echo "$cycle" > "$PROGRESS_FILE"
  log "회차 $cycle 기록 — ${incident_id:-없음} variant=$variant (${elapsed}s)"
done
log "완료 — $RESULTS_FILE ($(wc -l < "$RESULTS_FILE" | tr -d ' ') 행)"
