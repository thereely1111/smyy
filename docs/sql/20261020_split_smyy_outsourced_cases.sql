-- =====================================================================
-- ⛔ 已被路径方案取代，勿执行 ⛔
--
-- 本脚本的「主表 + entries 子表」拆分路线与最终定案的数据模型冲突：
-- 正文不再进库，改为写入 public/cases/<protoId>/<板块>/，库表只存相对路径。
-- 请改用 docs/sql/20261020_smyy_case_content_paths.sql。
--
-- 历史：本脚本的建表部分曾被执行过（库里已存在空表 smyy_outsourced_cases_entries），
--       搬迁 / 删列部分从未真正落库。该空表可在全量迁移完成后一并删除。
-- =====================================================================

-- =====================================================================
-- 拆分外包案例表：smyy_outsourced_cases -> 主表 + 子表(entries)
--
-- 参照 smyy 库中 public.agent_session_extensions / agent_session_extension_entries
-- 的「主表(元数据) + 子表(key-value 内容)」结构：
--   * 主表只保留案例的身份/索引字段；
--   * 子表 smyy_outsourced_cases_entries 以 (case_id, entry_key) 唯一存储各内容字段，
--     相当于一个文档的各 field，后续新增板块无需改表结构。
--
-- 迁移映射（内容列 -> entry_key，键名与后端 DTO / 前端字段一致）：
--   guide_html              -> guideHtml
--   animation_html          -> animationHtml
--   animation_segments_json -> animationSegmentsJson
--   code_text               -> codeText
--   result_html             -> resultHtml
--   replay_html             -> replayHtml
--   guide_file_name         -> guideFileName
--   animation_file_name     -> animationFileName
--   code_file_name          -> codeFileName
--   result_file_name        -> resultFileName
--   guide_files_json        -> guideFilesJson
--   code_files_json         -> codeFilesJson
--   result_files_json       -> resultFilesJson
--
-- 脚本可重复执行：内容列尚存在时才做搬迁与删列，否则为空操作。
-- 注意：执行后主表不再有上述内容列，按扁平列读写的旧后端会报缺列错误，
--       需后端按主子表结构适配后再上线。
-- =====================================================================

BEGIN;

-- 1) 子表：key-value 条目（结构对齐 agent_session_extension_entries）
CREATE TABLE IF NOT EXISTS smyy_outsourced_cases_entries (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id     UUID         NOT NULL REFERENCES smyy_outsourced_cases (id) ON DELETE CASCADE,
    entry_key   VARCHAR(128) NOT NULL,
    entry_value TEXT,
    UNIQUE (case_id, entry_key)
);

CREATE INDEX IF NOT EXISTS idx_outsourced_case_entries_case
    ON smyy_outsourced_cases_entries (case_id);

-- 2) 数据搬迁 + 3) 主表删列（仅当内容列仍存在时执行，保证可重复运行）
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'smyy_outsourced_cases'
          AND column_name = 'guide_html'
    ) THEN
        INSERT INTO smyy_outsourced_cases_entries (case_id, entry_key, entry_value)
        SELECT id, 'guideHtml', guide_html
        FROM smyy_outsourced_cases WHERE guide_html IS NOT NULL
        ON CONFLICT (case_id, entry_key) DO NOTHING;

        INSERT INTO smyy_outsourced_cases_entries (case_id, entry_key, entry_value)
        SELECT id, 'animationHtml', animation_html
        FROM smyy_outsourced_cases WHERE animation_html IS NOT NULL
        ON CONFLICT (case_id, entry_key) DO NOTHING;

        INSERT INTO smyy_outsourced_cases_entries (case_id, entry_key, entry_value)
        SELECT id, 'animationSegmentsJson', animation_segments_json
        FROM smyy_outsourced_cases WHERE animation_segments_json IS NOT NULL
        ON CONFLICT (case_id, entry_key) DO NOTHING;

        INSERT INTO smyy_outsourced_cases_entries (case_id, entry_key, entry_value)
        SELECT id, 'codeText', code_text
        FROM smyy_outsourced_cases WHERE code_text IS NOT NULL
        ON CONFLICT (case_id, entry_key) DO NOTHING;

        INSERT INTO smyy_outsourced_cases_entries (case_id, entry_key, entry_value)
        SELECT id, 'resultHtml', result_html
        FROM smyy_outsourced_cases WHERE result_html IS NOT NULL
        ON CONFLICT (case_id, entry_key) DO NOTHING;

        INSERT INTO smyy_outsourced_cases_entries (case_id, entry_key, entry_value)
        SELECT id, 'replayHtml', replay_html
        FROM smyy_outsourced_cases WHERE replay_html IS NOT NULL
        ON CONFLICT (case_id, entry_key) DO NOTHING;

        INSERT INTO smyy_outsourced_cases_entries (case_id, entry_key, entry_value)
        SELECT id, 'guideFileName', guide_file_name
        FROM smyy_outsourced_cases WHERE guide_file_name IS NOT NULL
        ON CONFLICT (case_id, entry_key) DO NOTHING;

        INSERT INTO smyy_outsourced_cases_entries (case_id, entry_key, entry_value)
        SELECT id, 'animationFileName', animation_file_name
        FROM smyy_outsourced_cases WHERE animation_file_name IS NOT NULL
        ON CONFLICT (case_id, entry_key) DO NOTHING;

        INSERT INTO smyy_outsourced_cases_entries (case_id, entry_key, entry_value)
        SELECT id, 'codeFileName', code_file_name
        FROM smyy_outsourced_cases WHERE code_file_name IS NOT NULL
        ON CONFLICT (case_id, entry_key) DO NOTHING;

        INSERT INTO smyy_outsourced_cases_entries (case_id, entry_key, entry_value)
        SELECT id, 'resultFileName', result_file_name
        FROM smyy_outsourced_cases WHERE result_file_name IS NOT NULL
        ON CONFLICT (case_id, entry_key) DO NOTHING;

        INSERT INTO smyy_outsourced_cases_entries (case_id, entry_key, entry_value)
        SELECT id, 'guideFilesJson', guide_files_json
        FROM smyy_outsourced_cases WHERE guide_files_json IS NOT NULL
        ON CONFLICT (case_id, entry_key) DO NOTHING;

        INSERT INTO smyy_outsourced_cases_entries (case_id, entry_key, entry_value)
        SELECT id, 'codeFilesJson', code_files_json
        FROM smyy_outsourced_cases WHERE code_files_json IS NOT NULL
        ON CONFLICT (case_id, entry_key) DO NOTHING;

        INSERT INTO smyy_outsourced_cases_entries (case_id, entry_key, entry_value)
        SELECT id, 'resultFilesJson', result_files_json
        FROM smyy_outsourced_cases WHERE result_files_json IS NOT NULL
        ON CONFLICT (case_id, entry_key) DO NOTHING;

        ALTER TABLE smyy_outsourced_cases
            DROP COLUMN guide_html,
            DROP COLUMN animation_html,
            DROP COLUMN animation_segments_json,
            DROP COLUMN code_text,
            DROP COLUMN result_html,
            DROP COLUMN replay_html,
            DROP COLUMN guide_file_name,
            DROP COLUMN animation_file_name,
            DROP COLUMN code_file_name,
            DROP COLUMN result_file_name,
            DROP COLUMN guide_files_json,
            DROP COLUMN code_files_json,
            DROP COLUMN result_files_json;
    END IF;
END $$;

COMMIT;
