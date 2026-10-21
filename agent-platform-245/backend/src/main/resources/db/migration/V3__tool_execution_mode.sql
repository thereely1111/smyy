-- M5：工具执行模式（internal 内部执行 / external 外部执行 + HITL 人工确认）

ALTER TABLE tools
    ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT 'internal';

-- 种子数据：文件读写改为 external，作为外部执行 / HITL 的演示入口
UPDATE tools
SET execution_mode = 'external'
WHERE name = '文件读写';
