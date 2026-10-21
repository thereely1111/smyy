-- V65: 外包案例非动画板块支持多文件
ALTER TABLE smyy_outsourced_cases
    ADD COLUMN IF NOT EXISTS guide_files_json TEXT,
    ADD COLUMN IF NOT EXISTS code_files_json TEXT,
    ADD COLUMN IF NOT EXISTS result_files_json TEXT;
