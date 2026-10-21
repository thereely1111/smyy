# agent-platform 前端运行时镜像（Vite 静态产物 + nginx）
# nginx 站点配置由 Helm Chart 的 ConfigMap 挂载覆盖 /etc/nginx/conf.d/default.conf，
# 镜像内的 default.conf 仅为本地裸跑兜底。
# 构建（项目根目录执行）：
#   cd frontend && npm run build
#   docker build -f docker/frontend/Dockerfile -t cr-harbor.tipdm.net/agentscope/agent-platform-frontend:<tag> .
# 注意：本 Dockerfile 配套使用同目录 Dockerfile.dockerignore（不排除 dist），
#       根 .dockerignore 仍排除 frontend/dist，但仅对本 Dockerfile 构建不生效。
FROM nginx:alpine

ENV TZ=Asia/Shanghai
COPY frontend/dist/ /usr/share/nginx/html/
COPY docker/frontend/default.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
