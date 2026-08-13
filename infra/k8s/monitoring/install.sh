#!/usr/bin/env bash
# 모니터링·로그 스택 설치 (5주차 Phase 2) — kube-prometheus-stack + Loki + Alloy.
# upgrade --install 이라 재실행 멱등 — Phase 5 빈 클러스터 재현 절차의 한 단계.
# Grafana 대시보드는 compose provisioning JSON 을 sidecar ConfigMap 으로 반입 (원본 한 곳 유지)
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$DIR/../../.." && pwd)"
NS="${NS:-monitoring}"

helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null
helm repo add grafana https://grafana.github.io/helm-charts >/dev/null
helm repo update >/dev/null

kubectl get namespace "$NS" >/dev/null 2>&1 || kubectl create namespace "$NS"

# 대시보드 ConfigMap — grafana_dashboard 라벨이 sidecar 수집 조건
kubectl -n "$NS" create configmap grafana-dashboards-aiops \
  --from-file="$ROOT/infra/grafana/provisioning/dashboards/json" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NS" label configmap grafana-dashboards-aiops grafana_dashboard=1 --overwrite

helm upgrade --install monitoring prometheus-community/kube-prometheus-stack \
  -n "$NS" -f "$DIR/values-kube-prometheus-stack.yaml"

helm upgrade --install loki grafana/loki \
  -n "$NS" -f "$DIR/values-loki.yaml"

helm upgrade --install alloy grafana/alloy \
  -n "$NS" -f "$DIR/values-alloy.yaml"

kubectl apply -f "$DIR/prometheusrule-target-app.yaml"

echo "완료 — 확인: kubectl -n $NS get pods"
