#!/usr/bin/env bash
# Secret 반입 (DAY 27 신설, 2026-08-28 확정) — .env 2곳에서 시크릿 키만 골라 K8s Secret 을 생성한다.
# 값은 stdout 에 출력하지 않는다. 확정 방식 (ADR-0016 Secret 관리): K8s Secret 직접 생성 + 차트 values 미기록 +
# compose 는 .env pass-through. 외부 Secret 매니저(External Secrets 등)는 단일 kind 클러스터 규모에서 범위 밖으로 판단.
#
# 원천 (git 미추적):
#   infra/.env          — OPENAI/SLACK 3종+웹훅/AUTH_CLIENT_SECRET 3종/ALERTMANAGER_WEBHOOK_SECRET (+LANGFUSE 2종은 미반입)
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
args_from "$INFRA_ENV" SLACK_WEBHOOK_URL SLACK_BOT_TOKEN SLACK_APP_TOKEN SLACK_APPROVAL_CHANNEL
# 웹훅 공유 시크릿 + 게이트웨이 호출용 클라이언트 시크릿 (ADR-0016) — 인증 항상 필수라 빈 값이면 control-plane 이 기동 실패로 드러난다.
# OPENAI_API_KEY 는 더 이상 반입하지 않는다 — 임베딩은 게이트웨이 경유 + OAuth 토큰
args_from "$INFRA_ENV" ALERTMANAGER_WEBHOOK_SECRET
v="$(getv "$INFRA_ENV" AUTH_CLIENT_SECRET_CONTROL_PLANE)"; [ -n "$v" ] && LITERALS+=("--from-literal=AUTH_CLIENT_SECRET=$v")
make_secret control-plane-secrets

LITERALS=()
args_from "$AGENT_ENV" ANTHROPIC_API_KEY
# MCP 인증 클라이언트 시크릿 (ADR-0016) — auth-server 등록값과 같은 원천, 앱이 읽는 키 이름으로 반입
v="$(getv "$INFRA_ENV" AUTH_CLIENT_SECRET_AGENT_SERVICE)"; [ -n "$v" ] && LITERALS+=("--from-literal=AUTH_CLIENT_SECRET=$v")
make_secret agent-service-secrets

# Alertmanager 가 마운트하는 웹훅 시크릿 파일 (kube-prometheus-stack alertmanagerSpec.secrets) — control-plane 과 같은 값
LITERALS=()
args_from "$INFRA_ENV" ALERTMANAGER_WEBHOOK_SECRET
make_secret alertmanager-webhook-secret

# llm-gateway — 채팅(ANTHROPIC)·임베딩/교차(OPENAI), 원천은 기존 2곳 공유
# SLACK_WEBHOOK_URL — 예산 임계 경고, control-plane 과 같은 원천 공유
LITERALS=()
args_from "$AGENT_ENV" ANTHROPIC_API_KEY
args_from "$INFRA_ENV" OPENAI_API_KEY SLACK_WEBHOOK_URL
make_secret llm-gateway-secrets

# auth-server (ADR-0016) — 클라이언트 시크릿 3종. 인증 항상 필수라 빈 값이면 pod 가 기동 실패로 드러난다
LITERALS=()
args_from "$INFRA_ENV" AUTH_CLIENT_SECRET_AGENT_SERVICE AUTH_CLIENT_SECRET_CONTROL_PLANE AUTH_CLIENT_SECRET_OPS_ADMIN
make_secret auth-server-secrets

echo "완료 — 확인: kubectl -n $NS get secrets"
