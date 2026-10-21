-- =====================================================================
-- 外包案例「正文外置」：smyy_outsourced_cases 只存相对路径
--
-- 取代 docs/sql/20261020_split_smyy_outsourced_cases.sql 的「主表 + entries 子表」路线：
--   正文不再落库，改为写入项目内容根 public/cases/<protoId>/<板块>/<文件>
--   （板块 = guide / animation / code / result / replay），库里只留相对路径。
--   读回时由后端 SmyyCaseContentResolver 按路径读文件、拼回原字段形状。
--
-- 列语义变化：
--   guide_files_json / code_files_json / result_files_json
--       内联 [{name,text}]                -> [{name,path}]
--   animation_segments_json
--       内联 [{key,label,fileName,html}]  -> [{key,label,fileName,path}]
--   guide_file_name / animation_file_name / code_file_name / result_file_name
--       原先只存“文件名”                   -> 存“相对内容根的路径”（如 p3/guide/p3_case_guide_01_v6.html）
--   replay_file_name                      新增，存复现文件相对路径
--   guide_html / animation_html / code_text / result_html / replay_html
--       逐步清空，全量迁移并与其他连库实例协调后再删除（见文末）。
--
-- ⚠ 本库是多机共享库（192.168.20.152/165/214/140 等后端同时连着）：
--   * ADD COLUMN 是向后兼容的（旧代码忽略新列），可安全先执行；
--   * DROP COLUMN 会让跑旧代码的实例启动失败，必须等所有实例都升级后再单独执行；
--   * 不走 Flyway（该库已有 V75-V78 外来版本，校验历史不稳定），手工执行即可。
-- =====================================================================

BEGIN;

-- 1) 新增复现文件路径列（幂等，可重复执行）
ALTER TABLE smyy_outsourced_cases
    ADD COLUMN IF NOT EXISTS replay_file_name VARCHAR(220);

COMMIT;

-- =====================================================================
-- 2) 逐案例迁移（垂直切片先做 p3；其余 p1/p2/p4/p5/p6/p7/p9 按同样方式补）
--
--    正文文件由导出脚本落盘（本仓库 public/cases/<protoId>/<板块>/），
--    落盘前后做过 UTF-8 字节级回环校验（读回 == 库里原值）才更新路径。
--    下面以 p3 为样板记录等价 SQL（导出工具已执行同一批更新）：
--
-- UPDATE smyy_outsourced_cases SET
--   guide_file_name         = 'p3/guide/p3_case_guide_01_v6.html',
--   guide_files_json        = '[{"name":"p3_case_guide_01_v6.html","path":"p3/guide/p3_case_guide_01_v6.html"}]',
--   animation_file_name     = 'p3/animation/p3_microgrid-power-dispatch_animation_v6.html',
--   animation_segments_json = '[{"key":"global","label":"全局分析","fileName":"p3_microgrid-power-dispatch_animation_01.html","path":"p3/animation/p3_microgrid-power-dispatch_animation_01.html"}, …]',
--   code_file_name          = 'p3/code/p3_case_code_01_v6.py',
--   code_files_json         = '[{"name":"p3_case_code_01_v6.py","path":"p3/code/p3_case_code_01_v6.py"}]',
--   result_file_name        = 'p3/result/p3_case_result_01_v6.html',
--   result_files_json       = '[{"name":"p3_case_result_01_v6.html","path":"p3/result/p3_case_result_01_v6.html"}]',
--   replay_file_name        = 'p3/replay/p3_case_replay_01_v6.html',
--   guide_html = NULL, animation_html = NULL, code_text = NULL, result_html = NULL, replay_html = NULL
-- WHERE proto_id = 'p3';
-- =====================================================================

-- =====================================================================
-- 3)（最后一步，需协调）全量迁移完成后删除内联内容列
--
-- ALTER TABLE smyy_outsourced_cases
--     DROP COLUMN guide_html,
--     DROP COLUMN animation_html,
--     DROP COLUMN code_text,
--     DROP COLUMN result_html,
--     DROP COLUMN replay_html;
--
-- 同时删掉拆分路子留下的空子表（若有）：
-- DROP TABLE IF EXISTS smyy_outsourced_cases_entries;
-- =====================================================================
