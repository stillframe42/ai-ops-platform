{{/* 공통 라벨 — selector·pod 라벨(app)은 계약(Service selector·Alloy service 라벨)이라 불변, 메타데이터 라벨만 표준화 */}}
{{- define "llm-gateway.labels" -}}
app: llm-gateway
app.kubernetes.io/name: llm-gateway
app.kubernetes.io/part-of: aiops
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}
