-- 资料库核心表（P1）：空间 + 节点树 + 节点级授权
--
-- 设计要点：
-- 1) node 表是「薄壳」：只管树结构与元信息，各 kind 的正文不进本表
--    （doc→markdown 文件、database→表格两表、drive→文件字节、link→剪藏正文），
--    新增 kind 时无需改动本表。
-- 2) parent_id 与 node_path 双写：读子树/ 读祖先链走 path（一次 LIKE 命中），
--    移动节点时改父指针 + 重写子树 path（写贵换读快，本项目树浅写少）。
-- 3) 授权挂在节点上，且向祖先冒泡（见 library_node_grants 的 space_id 冗余），
--    使「子树整体授权」与「单节点授权」共用一张表。

CREATE TABLE library_spaces (
    id              UUID PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    -- personal：每人一个个人空间（后端按用户惰性创建）；team：多人协作空间
    category        VARCHAR(16) NOT NULL CHECK (category IN ('personal', 'team')),
    owner_user_id   UUID NOT NULL REFERENCES users (id),
    -- 空间级默认权限：节点未单独授权时回退到此值（null = 仅 owner）
    default_permission VARCHAR(8) CHECK (default_permission IN ('READ', 'WRITE')),
    description     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_library_spaces_owner ON library_spaces (owner_user_id);
-- 每个用户至多一个个人空间；团队空间数量不限（故唯一性只约束 personal 分支，
-- 不能用 UNIQUE(owner_user_id, category)——那会把团队空间也压成每人一个）
CREATE UNIQUE INDEX uq_library_spaces_personal
    ON library_spaces (owner_user_id) WHERE category = 'personal';

CREATE TABLE library_nodes (
    id              UUID PRIMARY KEY,
    space_id        UUID NOT NULL REFERENCES library_spaces (id) ON DELETE CASCADE,
    -- 自引用父节点；根节点为 NULL。深度上限（10 层）在 service 层拦截，不靠 DB 约束。
    parent_id       UUID REFERENCES library_nodes (id) ON DELETE CASCADE,
    -- 物化路径：以 '/' 分隔的 id 序列（含自身），如 '/<rootId>/<childId>/'。
    -- 首尾保留分隔符，使前缀匹配天然只命中子树。
    node_path       TEXT NOT NULL,
    kind            VARCHAR(16) NOT NULL CHECK (kind IN
                        ('folder', 'doc', 'database', 'web', 'link', 'drive', 'knowledge')),
    title           VARCHAR(255) NOT NULL,
    -- 同级排序位（越小越靠前）；新增时取同parent 下 max+1
    sort_order      INTEGER NOT NULL DEFAULT 0,
    -- 载荷外置的引用：kind='knowledge' 时指向 knowledge_bases.id；其余 kind 为 NULL。
    -- 不建外键约束，避免与知识库模块产生双向生命周期耦合。
    payload_kb_id   UUID,
    -- 正文的存储相对路径（doc 的 markdown 文件、drive 的文件字节），DB 只存相对路径
    content_rel     VARCHAR(512),
    -- 预览用的摘要（列表页展示，不读正文文件）
    excerpt         TEXT,
    created_by      UUID NOT NULL REFERENCES users (id),
    -- 软删：保留行以维持子树 path 不被破坏；读取一律过滤 deleted_at IS NULL
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 载荷引用只在 knowledge 节点上出现
    CHECK (kind <> 'knowledge' OR payload_kb_id IS NOT NULL)
);

-- 列子节点（同空间内的直接子级）——最热的查询
CREATE INDEX idx_library_nodes_parent ON library_nodes (space_id, parent_id, sort_order)
    WHERE deleted_at IS NULL;
-- 按 path 前缀取子树（移动子树后重写 path，走此索引）
CREATE INDEX idx_library_nodes_path ON library_nodes (node_path text_pattern_ops)
    WHERE deleted_at IS NULL;
-- 按载荷反查（把已挂载的知识库找出来）
CREATE INDEX idx_library_nodes_kb ON library_nodes (payload_kb_id) WHERE payload_kb_id IS NOT NULL;

-- 节点级授权：把READ / WRITE 授予用户或群组。
-- space_id 冗余一份，使「按空间批量查授权」不必 join library_nodes。
CREATE TABLE library_node_grants (
    id              UUID PRIMARY KEY,
    node_id         UUID NOT NULL REFERENCES library_nodes (id) ON DELETE CASCADE,
    space_id        UUID NOT NULL REFERENCES library_spaces (id) ON DELETE CASCADE,
    principal_type  VARCHAR(8) NOT NULL CHECK (principal_type IN ('USER', 'GROUP')),
    principal_id    UUID NOT NULL,
    permission      VARCHAR(8) NOT NULL CHECK (permission IN ('READ', 'WRITE')),
    granted_by      UUID NOT NULL REFERENCES users (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (node_id, principal_type, principal_id)
);

CREATE INDEX idx_library_grants_principal ON library_node_grants (principal_type, principal_id);
CREATE INDEX idx_library_grants_node ON library_node_grants (node_id);
CREATE INDEX idx_library_grants_space ON library_node_grants (space_id);