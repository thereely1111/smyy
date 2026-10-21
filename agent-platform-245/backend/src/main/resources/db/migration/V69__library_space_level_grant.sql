-- 资料库团队空间成员授权：让 library_node_grants 同时承载「空间级」与「节点级」授权。
--
-- 设计要点：
-- 1) node_id 改为可空：NULL 表示「整空间授权」（团队空间成员），
--    非 NULL 表示原有的节点级授权（沿祖先链冒泡覆盖子树）。
--    一张表两档语义，避免为空间成员另开一张表——判权时本来就只查这一张。
-- 2) 原 UNIQUE (node_id, principal_type, principal_id) 必须换掉：
--    PostgreSQL 的 UNIQUE 约束中 NULL 不参与相等判定（NULL != NULL），
--    保留它则同一主体的多条空间级授权可以无限重复插入。
--    改为两个部分唯一索引，各自只在 node_id IS / IS NOT NULL 的分支上生效。
-- 3) 空间级授权的判权走 space_id 冗余列（沿用原设计），不必 join library_nodes。
--
-- ⚠️ **本文件必须全幂等**：建索引一律 IF NOT EXISTS、删约束一律 IF EXISTS。
-- 起因是一次真实事故——本文件最初以 V67 命名落库（成功执行，建好了全部索引），
-- 随后只把文件名改成 V69 而未清理 target/classes 里的旧编译产物，
-- 启动时 Flyway 扫到 target 里残留的 V67 先跑一遍、紧接着跑 V69，
-- 后者在 CREATE INDEX 处撞名（42P07 relation already exists）导致启动失败。
-- 幂等写法让「DDL 已生效但历史记录对不上」这类状态可安全重跑，不必手工改库。

-- 先删原表级唯一约束再建部分索引：约束先移除，避免迁移中途出现「同名两套唯一性」。
-- IF EXISTS 兜底：约束名按 PG 默认规则推导（<表>_<列...>_key），若 V63 实际生成的名字不同，
-- 没有这句迁移会整条失败、卡在启动阶段；部分唯一索引本身已能保证唯一性，
-- 故旧约束删不掉也只是多留一条冗余约束，不会造成数据错误。
ALTER TABLE library_node_grants
    DROP CONSTRAINT IF EXISTS library_node_grants_node_id_principal_type_principal_id_key;

-- 重复执行本句无害（已是可空则不改动）
ALTER TABLE library_node_grants ALTER COLUMN node_id DROP NOT NULL;

-- 节点级：同一 (node_id, principal) 仅一条
CREATE UNIQUE INDEX IF NOT EXISTS uq_library_grants_node_principal
    ON library_node_grants (node_id, principal_type, principal_id)
    WHERE node_id IS NOT NULL;

-- 空间级：同一 (space_id, principal) 仅一条
CREATE UNIQUE INDEX IF NOT EXISTS uq_library_grants_space_principal
    ON library_node_grants (space_id, principal_type, principal_id)
    WHERE node_id IS NULL;

-- 空间级判权路径：按「主体 → 空间」反查我参与的所有空间（列可见空间时走这里）
CREATE INDEX IF NOT EXISTS idx_library_grants_space_level_principal
    ON library_node_grants (principal_type, principal_id)
    WHERE node_id IS NULL;
