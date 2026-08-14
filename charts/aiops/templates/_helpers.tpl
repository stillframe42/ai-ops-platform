{{/* umbrella 자체 리소스(대시보드 CM·PrometheusRule)의 공통 라벨 */}}
{{- define "aiops.labels" -}}
app.kubernetes.io/part-of: aiops
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}
