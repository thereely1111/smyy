#!/bin/bash
# Agent Platform 后端启动脚本
# 用法：./start_with_monitor.sh

set -u

PROJECT_DIR="/opt/smyy/agent-platform-java"
JAVA_BIN="/opt/java21/bin/java"
JAR_FILE="$PROJECT_DIR/backend/target/agent-platform-backend-0.1.0-SNAPSHOT.jar"
PORT="18080"                         # 只需要改这里即可换端口
ENV_FILE="/etc/agent-platform/backend.env"
LOG_FILE="$PROJECT_DIR/backend/backend_$(date +%Y%m%d_%H%M%S).log"

cd "$PROJECT_DIR" || { echo "无法进入项目目录: $PROJECT_DIR"; exit 1; }

# 读取数据库、Redis、密钥等配置
if [ -f "$ENV_FILE" ]; then
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
fi

# 以脚本顶部端口为准；如需通过环境变量覆盖，可执行 SERVER_PORT=18081 ./scripts/start_with_monitor.sh
PORT="${SERVER_PORT:-$PORT}"

if [ ! -x "$JAVA_BIN" ]; then
    echo "找不到 Java 21: $JAVA_BIN"
    exit 1
fi

if [ ! -f "$JAR_FILE" ]; then
    echo "找不到 JAR 包: $JAR_FILE"
    exit 1
fi

echo "清理旧的后端进程..."
pkill -f "$JAR_FILE" 2>/dev/null || true
sleep 2

if command -v lsof >/dev/null 2>&1 && lsof -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "端口 $PORT 仍被占用，停止占用进程..."
    fuser -k "$PORT"/tcp 2>/dev/null || true
    sleep 1
fi

echo "启动后端，端口: $PORT"
echo "日志文件: $LOG_FILE"

nohup "$JAVA_BIN" \
    -Dserver.port="$PORT" \
    -jar "$JAR_FILE" \
    > "$LOG_FILE" 2>&1 < /dev/null &

PID=$!
echo "进程 PID: $PID"

echo "等待后端启动..."
sleep 5

if ps -p "$PID" >/dev/null 2>&1; then
    echo "进程已启动"
    if curl -s -f --max-time 5 "http://127.0.0.1:$PORT/actuator/health" >/dev/null 2>&1; then
        echo "后端健康检查通过: http://127.0.0.1:$PORT/actuator/health"
    else
        echo "进程存在，但健康检查暂未通过，请查看日志:"
        tail -30 "$LOG_FILE"
    fi
else
    echo "后端启动失败，请查看日志:"
    tail -50 "$LOG_FILE"
    exit 1
fi
