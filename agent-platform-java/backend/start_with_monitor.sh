#!/bin/bash

BACKEND_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
JAVA_BIN="/opt/java21/bin/java"
JAR_FILE="$BACKEND_DIR/target/agent-platform-backend-0.1.0-SNAPSHOT.jar"
PORT=18081
LOG_FILE="$BACKEND_DIR/backend.log"

cd "$BACKEND_DIR" || exit 1
[ -x "$JAVA_BIN" ] || { echo "Java not found: $JAVA_BIN"; exit 1; }
[ -f "$JAR_FILE" ] || { echo "JAR not found: $JAR_FILE"; exit 1; }

# Match only Java processes running this exact JAR, including relative paths.
find_backend_pids() {
    local proc pid exe arg previous candidate cwd
    for proc in /proc/[0-9]*; do
        pid=${proc##*/}
        [ "$pid" = "$$" ] && continue
        exe=$(readlink "$proc/exe" 2>/dev/null) || continue
        case "$exe" in */bin/java) ;; *) continue ;; esac
        previous=""
        while IFS= read -r -d '' arg; do
            if [ "$previous" = "-jar" ]; then
                candidate="$arg"
                if [[ "$candidate" != /* ]]; then
                    cwd=$(readlink "$proc/cwd" 2>/dev/null) || break
                    candidate="$cwd/$candidate"
                fi
                candidate=$(readlink -f "$candidate" 2>/dev/null)
                if [ "$candidate" = "$JAR_FILE" ]; then
                    echo "$pid"
                fi
                break
            fi
            previous="$arg"
        done < "$proc/cmdline" 2>/dev/null
    done
}

echo "Stopping old backend processes..."
for pid in $(find_backend_pids); do
    kill "$pid" 2>/dev/null || true
done
for ((attempt=0; attempt<10; attempt++)); do
    [ -z "$(find_backend_pids)" ] && break
    sleep 1
done
for pid in $(find_backend_pids); do
    echo "Force stopping backend PID: $pid"
    kill -9 "$pid" 2>/dev/null || true
done
sleep 1

if ss -lnt | awk -v port="$PORT" '$4 ~ (":" port "$") { found=1 } END { exit !found }'; then
    echo "Port $PORT is occupied; not stopping unrelated services."
    exit 1
fi

# 工作区根与落盘日志交给后端自推导（优先仓库根 workspace，找不到才回落启动目录），
# 这里不要显式指定：显式声明会按住自推导，并把工作区锁死在被 gitignore 的 backend/workspace
echo "Port: $PORT"
echo "Log: $LOG_FILE"
nohup "$JAVA_BIN" -Dserver.port="$PORT" \
    -jar "$JAR_FILE" >> "$LOG_FILE" 2>&1 < /dev/null &
PID=$!
echo "$PID" > "$BACKEND_DIR/backend.pid"
sleep 3
if kill -0 "$PID" 2>/dev/null; then
    echo "Backend process running, PID: $PID"
else
    echo "Backend exited. Recent log:"
    tail -30 "$LOG_FILE"
    exit 1
fi
