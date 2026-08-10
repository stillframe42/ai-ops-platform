#!/usr/bin/env bash
# 임시 Secret 반입 (5주차 DAY 27, weekly-plan Phase 1 ⑩) — .env 2곳에서 시크릿 키만 골라
# K8s Secret 을 생성한다. 값은 stdout 에 출력하지 않는다.
# 임시 방식: 8월 보안 주간에 Secret 관리 체계(외부 Secret 저장소 등)를 재검토한다.
#
# 원천 (git 미추적):
#   infra/.env          — OPENAI/MCP_API_KEY/SLACK 3종+웹훅 (+LANGFUSE 2종은 주간 한정 비활성으로 미반입)
#   agent-service/.env  — ANTHROPIC_API_KEY (필수)
# POSTGRES_USER/PASSWORD 는 compose 와 같은 규칙: .env 또는 셸 env 미설정 시 로컬 데모 기본값(aiops)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
INFRA_ENV="$ROOT/infra/.env"
AGENT_ENV="$ROOT/agent-service/.env"
NS="${NS:-aiops}"

# env 파일에서 키 하나를 읽는다 (없으면 빈 문자열 — 키-게이트 관례상 미반입 허용)
getv() { grep -E "^$2=" "$1" 2>/dev/null | head -1 | cut -d= -f2- || true; }

kubectl get namespace "$NS" >/dev/null 2>&1 || kubectl create namespace "$NS"

# 시크릿 항목을 모아 --from-literal 인자 배열을 만든다. 빈 값 키는 넣지 않는다
# (compose pass-through 와 같은 의미론 — 미설정 = 변수 자체 미전달, 키-게이트 비활성)
args_from() { # $1=env파일 $2...=키 목록 → 전역 배열 LITERALS 에 축적
  local f="$1"; shift
  for k in "$@"; do
    local v; v="$(getv "$f" "$k")"
    [ -n "$v" ] && LITERALS+=("--from-literal=$k=$v")
  done
}

make_secret() { # $1=이름, LITERALS 사용 — apply 로 멱등
  if [ "${#LITERALS[@]}" -eq 0 ]; then echo "  $1: 반입할 키 없음 (생략)"; return; fi
  kubectl -n "$NS" create secret generic "$1" "${LITERALS[@]}" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  echo "  $1: 키 ${#LITERALS[@]}건 반입"
}

PG_USER="${POSTGRES_USER:-$(getv "$INFRA_ENV" POSTGRES_USER)}"
PG_PASS="${POSTGRES_PASSWORD:-$(getv "$INFRA_ENV" POSTGRES_PASSWORD)}"
PG_USER="${PG_USER:-aiops}"
PG_PASS="${PG_PASS:-aiops}"

echo "namespace=$NS 에 Secret 생성:"

LITERALS=("--from-literal=POSTGRES_USER=$PG_USER" "--from-literal=POSTGRES_PASSWORD=$PG_PASS")
make_secret postgres-secrets

LITERALS=("--from-literal=POSTGRES_USER=$PG_USER" "--from-literal=POSTGRES_PASSWORD=$PG_PASS")
args_from "$INFRA_ENV" OPENAI_API_KEY MCP_API_KEY SLACK_WEBHOOK_URL SLACK_BOT_TOKEN SLACK_APP_TOKEN SLACK_APPROVAL_CHANNEL
make_secret control-plane-secrets

LITERALS=()
args_from "$AGENT_ENV" ANTHROPIC_API_KEY
args_from "$INFRA_ENV" MCP_API_KEY   # control-plane 과 같은 원천 공유 (compose 관례 승계)
make_secret agent-service-secrets

echo "완료 — 확인: kubectl -n $NS get secrets"
