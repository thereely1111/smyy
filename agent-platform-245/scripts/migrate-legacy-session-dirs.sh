#!/bin/bash
# =============================================================================
# 历史会话产物迁移脚本（一次性）
#
# 背景：工作区改造前，会话产物目录（wb-{sessionId}/全局DB/...）由模型自行创建，
# 可能落在仓库根目录或智能体定义区内；改造后运行态产物统一托管于
# workspace/runtimes/{agentId}/{userId}/{sessionId}/。
#
# 本脚本将旧会话目录归档迁移至 workspace/runtimes/{agentId}/legacy/{sessionId}/，
# 旧数据仅只读归档、不参与新会话，保留现场以便人工核查。
#
# 用法：
#   migrate-legacy-session-dirs.sh -w <workspace-root> -a <agentId> -s <旧目录1> [<旧目录2> ...]
#
# 示例：
#   migrate-legacy-session-dirs.sh \
#     -w /Volumes/Data/Work/project/agent-platform-java/workspace \
#     -a 631f97d0-c074-3069-8196-e850831bbee0 \
#     -s /Volumes/Data/Work/project/agent-platform-java/wb-a3abdb3d-d7ee-4910-b84d-e8ce8977eed9 \
#        /Volumes/Data/Work/project/agent-platform-java/wb-a66a6bed-4f9e-4d7a-9b5c-3e8f1a2b3c4d
#
# 注意：迁移前请确认由哪个人工用户持有这些旧产物（归档到 legacy/ 下统一管理），
# 如确认无价值，直接删除旧目录即可，无需迁移。
# =============================================================================
set -euo pipefail

usage() {
    sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
}

WORKSPACE_ROOT=""
AGENT_ID=""
declare -a SOURCES=()

while getopts "w:a:s:h" opt; do
    case "$opt" in
        w) WORKSPACE_ROOT="$OPTARG" ;;
        a) AGENT_ID="$OPTARG" ;;
        s) SOURCES+=("$OPTARG") ;;
        h) usage ;;
        *) usage ;;
    esac
done

if [[ -z "$WORKSPACE_ROOT" || -z "$AGENT_ID" || ${#SOURCES[@]} -eq 0 ]]; then
    usage
fi

RUNTIME_DIR="$WORKSPACE_ROOT/runtimes/$AGENT_ID/legacy"
mkdir -p "$RUNTIME_DIR"

for src in "${SOURCES[@]}"; do
    if [[ ! -d "$src" ]]; then
        echo "[skip] 目录不存在: $src"
        continue
    fi
    session_id=$(basename "$src")
    dst="$RUNTIME_DIR/$session_id"
    if [[ -e "$dst" ]]; then
        echo "[skip] 目标已存在（可能已迁移）: $dst"
        continue
    fi
    mv "$src" "$dst"
    echo "[moved] $src -> $dst"
done

echo "迁移完成。归档目录: $RUNTIME_DIR （旧会话数据只读归档，不参与新会话）"