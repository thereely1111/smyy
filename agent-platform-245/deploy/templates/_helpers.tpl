{{/*
agent-platform Helm helpers
*/}}

{{- define "agent-platform.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "agent-platform.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s" (default .Chart.Name .Values.nameOverride) | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{- define "agent-platform.labels" -}}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
app.kubernetes.io/name: {{ include "agent-platform.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "agent-platform.selectorLabels" -}}
app.kubernetes.io/name: {{ include "agent-platform.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/* 组件标签：component=backend|frontend|postgres|redis。调用方式：include "agent-platform.componentLabels" (dict "root" $ "component" "backend") */}}
{{- define "agent-platform.componentLabels" -}}
{{- include "agent-platform.selectorLabels" .root }}
app.kubernetes.io/component: {{ .component }}
{{- end }}

{{/*
敏感值统一 Secret 键名。existingSecret 模式下要求同名键存在：
  jwt-secret / external-key / db-password / redis-password
*/}}
{{- define "agent-platform.secretName" -}}
{{- if .Values.secrets.existingSecret }}
{{- .Values.secrets.existingSecret }}
{{- else }}
{{- include "agent-platform.fullname" . }}-secrets
{{- end }}
{{- end }}

{{/*
数据库连接信息：
- postgres.enabled=true -> 内置 Service
- 否则 externalDatabase
*/}}
{{- define "agent-platform.dbHost" -}}
{{- if .Values.postgres.enabled }}
{{- printf "%s-postgres" (include "agent-platform.fullname" .) }}
{{- else }}
{{- required "postgres.enabled=false 时必须设置 externalDatabase.host" .Values.externalDatabase.host }}
{{- end }}
{{- end }}

{{- define "agent-platform.dbPort" -}}
{{- if .Values.postgres.enabled }}{{ .Values.postgres.port }}{{- else }}{{ .Values.externalDatabase.port }}{{- end }}
{{- end }}

{{- define "agent-platform.dbName" -}}
{{- if .Values.postgres.enabled }}{{ .Values.postgres.database }}{{- else }}{{ .Values.externalDatabase.name }}{{- end }}
{{- end }}

{{- define "agent-platform.dbUser" -}}
{{- if .Values.postgres.enabled }}{{ .Values.postgres.username }}{{- else }}{{ .Values.externalDatabase.username }}{{- end }}
{{- end }}

{{- define "agent-platform.redisHost" -}}
{{- if .Values.redis.enabled }}
{{- printf "%s-redis" (include "agent-platform.fullname" .) }}
{{- else }}
{{- required "redis.enabled=false 时必须设置 externalRedis.host" .Values.externalRedis.host }}
{{- end }}
{{- end }}

{{- define "agent-platform.redisPort" -}}
{{- if .Values.redis.enabled }}{{ .Values.redis.port }}{{- else }}{{ .Values.externalRedis.port }}{{- end }}
{{- end }}

{{/* 前端 nginx 反代的后端 Service */}}
{{- define "agent-platform.backendService" -}}
{{- .Values.frontend.backendService | default (printf "%s-backend" (include "agent-platform.fullname" .)) }}
{{- end }}
