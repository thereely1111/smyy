# agent-platform 后端运行时镜像
# 构建（项目根目录执行）：
#   mvn -o package -Dmaven.test.skip=true
#   docker build -f docker/backend/Dockerfile -t cr-harbor.tipdm.net/agentscope/agent-platform-backend:<tag> .
FROM agent-platform-jre21:21.0.12
# ↑ 本地离线构建的 JRE21 基础镜像（docker/base/Dockerfile.jre21）；
#   如网络可达可换回 eclipse-temurin:21-jre

ENV TZ=Asia/Shanghai
WORKDIR /app

COPY backend/target/agent-platform-backend-0.1.0-SNAPSHOT.jar /app/app.jar

# file-based agent 定义（workspace/agents/*）作为种子打进镜像；
# 启动时由 ENTRYPOINT 播种到 $AGENT_PLATFORM_WORKSPACE_ROOT/agents/
# （cp -n 不覆盖已存在文件，容器重启/升级不会吃掉运行期修改；
#   设 AGENT_PLATFORM_SEED_AGENTS=false 可关闭播种）
COPY workspace/agents /opt/agent-seed/agents

EXPOSE 8080
# JAVA_OPTS 由 Deployment 注入（堆上限等）
ENTRYPOINT ["sh", "-c", "if [ \"$AGENT_PLATFORM_SEED_AGENTS\" != \"false\" ] && [ -d /opt/agent-seed/agents ]; then SEED_DST=\"${AGENT_PLATFORM_WORKSPACE_ROOT:-/data/workspace}/agents\"; mkdir -p \"$SEED_DST\" && cp -rn /opt/agent-seed/agents/. \"$SEED_DST/\" && echo \"[seed] file-based agents -> $SEED_DST\"; fi; exec java $JAVA_OPTS -jar /app/app.jar"]
