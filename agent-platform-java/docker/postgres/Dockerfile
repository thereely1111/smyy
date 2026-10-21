# pgvector 固化镜像（基于官方 postgres:16-alpine）
#
# 背景：Alpine 的 apk 仅提供 PG18 版本的 pgvector，与运行中的 PG16 主版本冲突，
# 无法直接 `apk add postgresql-pgvector`。因此采用源码构建 pgvector 0.8.1 适配 PG16。
#
# 注意：本环境 Alpine 仓库中无法安装 clang/llvm（apk search 无 clang21/llvm21-dev），
# 故 `make` 的 bitcode 子步骤会失败（缺 clang）；但主产物 vector.so 在此之前已编译链接完成，
# 因此这里容忍 make 失败并显式拷贝已生成的 vector.so / vector.control / vector--*.sql。
FROM postgres:16-alpine

RUN apk add --no-cache build-base postgresql16-dev git \
    && git clone --depth 1 --branch v0.8.1 https://github.com/pgvector/pgvector.git /tmp/pgvector \
    && cd /tmp/pgvector \
    && (make PG_CONFIG=/usr/local/bin/pg_config || true) \
    && cp -f /tmp/pgvector/vector.so /usr/local/lib/postgresql/ \
    && cp -f /tmp/pgvector/vector.control /usr/local/share/postgresql/extension/ \
    && cp -f /tmp/pgvector/sql//vector*.sql /usr/local/share/postgresql/extension/ \
    && cd / \
    && rm -rf /tmp/pgvector \
    && apk del git build-base postgresql16-dev

# 预创建扩展（可选：容器首次 initdb 后自动建库时即带 vector；
# 但本项目由 Flyway V24 `CREATE EXTENSION IF NOT EXISTS vector` 负责，这里不强制，
# 仅确保 .so / control / sql 已就位即可）。
HEALTHCHECK --interval=5s --timeout=3s --retries=10 \
    CMD pg_isready -U "$POSTGRES_USER" || exit 1
