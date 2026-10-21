-- 系统配置网页可改：配置覆盖层
--
-- 背景：application.yml 在容器内是只读的（镜像里的文件，改了随 Pod 重建丢失），
-- 而 progress-watchdog / hitl-timeout / alert / upload-policy / skill-repository
-- 这五组在 deploy/values.yaml 里**没有对应的环境变量注入项**（grep 为 0），
-- 生产环境改它们只能走 extraEnv 注入 SPRING_APPLICATION_JSON 再 helm upgrade
-- 重建 Pod。本表提供「不重启就能改」的出口。
--
-- 本表是**覆盖层，不是第二份真源**：
--   生效值 =本表覆盖值（若存在） > 环境变量 > application.yml 默认值
-- 表内无行时行为与本迁移之前完全一致，部署方不需要任何改动。
--
-- 只存「覆盖值」，不存完整配置：yml 仍是默认值的唯一出处，运维改 yml 里的
-- 默认值只影响「本表没有该 key」的那些部署，避免两处默认值各改一半。
--
-- ⚠️ 收录范围由 SettingCatalog 决定，只包含经审计确认「消费方每次读 getter」
-- 的字段。以下几类一律不收录，因为它们改了不生效，页面给用户假成功更糟：
--   - agui-event-log.flush-interval-ms：值在 AguiEventPersistenceService 构造
--     器内提交给 scheduleWithFixedDelay，此后 Java 无 API 可改已注册任务的周期
--     （有 setter 但无启动后读取点，是死 setter）
--   - alert.retention-days：无任何清理任务消费（全仓 getRetentionDays 仅命中
--     getter 自身，仓储 deleteOlderThan 零调用者），是死配置
--   - auth.jwt-secret-key：字段可改，但三个消费方各自构造器内已派生成
--     final SecretKey，改字段对它们无效
--   - document-preview.* / library.root / knowledge-base.root：private final
--     + @Value 构造参数，语言级不可赋值
--   - agentscope.agui.*：框架 starter 读且存为 final，启动期取一次

CREATE TABLE IF NOT EXISTS platform_setting (
    -- 配置键，形如 progress-watchdog.stall-min-minutes（与 application.yml 里的路径一致）
    key         VARCHAR(160) PRIMARY KEY,
    -- 值的 JSON 标量文本：数字/布尔直接字面量，字符串与字符串数组为 JSON 字符串/数组
    value       TEXT         NOT NULL,
    -- 最后修改人（审计）
    updated_by  UUID,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE platform_setting IS '系统配置覆盖层：生效值优先于环境变量与 application.yml 默认值';

-- 按修改人排查「谁在什么时候动了哪个开关」用；不建索引——表规模是配置项数量（数十行），
-- 需求出现前不预付索引成本。
