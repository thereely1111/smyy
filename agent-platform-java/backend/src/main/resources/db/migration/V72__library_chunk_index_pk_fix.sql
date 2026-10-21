-- 修 V71 的表结构缺陷：library_chunk_index 缺 id 主键列
--
-- 缺陷：V71 把 node_id 直接当主键，而实体 LibraryChunkIndex 继承 BaseEntity
-- （@Id 映射到 id 列）。应用启动时 ddl-auto: validate 直接失败：
--   Schema-validation: missing column [id] in table [library_chunk_index]
--
-- 修正：对齐 V70 的写法——id 为独立主键，node_id 降为带唯一约束的普通列。
--
-- ⚠️ 不能改 V71：一旦它进过 Flyway history，改内容会让 checksum 校验失败。
--
-- ⚠️ **本脚本必须幂等可重入**：某些环境下本脚本可能已被手工执行过而 Flyway
-- 尚无记录（结构已修好、history 表缺行），此时重跑会因"列已存在"而失败。
-- 故每一步都用 IF EXISTS / IF NOT EXISTS 守卫，可安全重复执行。
-- （幂等窗口只覆盖本次开发；若将来本脚本已进history，改脚本会触发 checksum 校验失败。）

-- 1) 补id 列（已存在则跳过）
ALTER TABLE library_chunk_index
    ADD COLUMN IF NOT EXISTS id UUID;

-- 2) 存量行回填：id 取 node_id 本身，保证非空且稳定（此表每 node 至多一行）
UPDATE library_chunk_index
SET id = node_id
WHERE id IS NULL;

-- 3) node_id 交还主键职责：解除主键、补唯一约束
--    （若 id 已是主键则跳过，避免重复 ADD PRIMARY KEY 报错）
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'library_chunk_index_pkey'
    ) THEN
        -- 当前主键若建在 node_id 上，先解除
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint c
            JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
            WHERE c.conname = 'library_chunk_index_pkey' AND a.attname = 'id'
        ) THEN
            ALTER TABLE library_chunk_index DROP CONSTRAINT library_chunk_index_pkey;
        END IF;
    END IF;
END $$;

-- 4) 确保 id 是主键（若尚未成为主键才补）
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
        WHERE c.conrelid = 'library_chunk_index'::regclass
          AND c.contype = 'p' AND a.attname = 'id'
    ) THEN
        ALTER TABLE library_chunk_index ADD PRIMARY KEY (id);
    END IF;
END $$;

-- 5) node_id 唯一（PostgreSQL 15+ 支持 NULLS NOT DISTINCT；这里直接建普通唯一约束，
--    node_id 本身 NOT NULL，无需 NULL 语义）
CREATE UNIQUE INDEX IF NOT EXISTS uq_library_chunk_index_node
    ON library_chunk_index (node_id);

-- 6) id 非空（应用侧 BaseEntity.onCreate() 负责赋值，不依赖库默认值）
ALTER TABLE library_chunk_index ALTER COLUMN id SET NOT NULL;
