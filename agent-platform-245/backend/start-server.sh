#!/bin/bash
set -e

export DB_HOST=localhost   # 填写：数据库地址
export DB_PORT=5432
export DB_NAME=agent_platform_server

# 清除可能继承的旧设置，使用新版本自动推导。
unset AGENT_PLATFORM_WORKSPACE_ROOT

exec bash /opt/smyy/agent-platform-java/backend/start_with_monitor.sh
