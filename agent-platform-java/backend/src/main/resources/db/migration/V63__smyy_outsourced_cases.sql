-- V63: 数模演易外包案例库
--
-- 用途：承载当前外包交付的完整案例内容，与智能体运行会话/产物库隔离。
-- 后续智能体成熟后可另建 generated case 表或复用独立来源字段，不与本表混写。
CREATE TABLE IF NOT EXISTS smyy_outsourced_cases (
    id              UUID PRIMARY KEY,
    proto_id        VARCHAR(32) NOT NULL UNIQUE,
    slug            VARCHAR(128) NOT NULL UNIQUE,
    title           VARCHAR(200) NOT NULL,
    tag             VARCHAR(80),
    category        VARCHAR(80),
    source          VARCHAR(40) NOT NULL DEFAULT 'OUTSOURCED',
    status          VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('DRAFT', 'PUBLISHED', 'BLANK')),
    guide_html      TEXT,
    animation_html  TEXT,
    code_text       TEXT,
    result_html     TEXT,
    guide_file_name VARCHAR(220),
    animation_file_name VARCHAR(220),
    code_file_name  VARCHAR(220),
    result_file_name VARCHAR(220),
    version         INTEGER NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_smyy_outsourced_cases_status ON smyy_outsourced_cases(status);

-- 初始两条已完成案例。正文由当前前端内置内容兜底展示，后续可在【案例管理】中替换为数据库正文。
INSERT INTO smyy_outsourced_cases (
    id, proto_id, slug, title, tag, category, source, status,
    guide_file_name, animation_file_name, code_file_name, result_file_name
) VALUES
(
    '11111111-1111-4111-8111-111111111103',
    'p3',
    'microgrid-power-dispatch',
    '微网与外部电网的电力调控策略',
    '2026国赛C题',
    '国赛本科',
    'OUTSOURCED',
    'PUBLISHED',
    'p3_microgrid-power-dispatch_guide_v1.html',
    'p3_microgrid-power-dispatch_animation_v1.html',
    'p3_microgrid-power-dispatch_code_v1.py',
    'p3_microgrid-power-dispatch_result_v1.html'
),
(
    '11111111-1111-4111-8111-111111111105',
    'p5',
    'sem-ad-optimization',
    'SEM 广告投放策略优化',
    '2026国赛E题',
    '国赛专科',
    'OUTSOURCED',
    'PUBLISHED',
    'p5_sem-ad-optimization_guide_v1.html',
    'p5_sem-ad-optimization_animation_v1.html',
    'p5_sem-ad-optimization_code_v1.py',
    'p5_sem-ad-optimization_result_v1.html'
)
ON CONFLICT (proto_id) DO NOTHING;
