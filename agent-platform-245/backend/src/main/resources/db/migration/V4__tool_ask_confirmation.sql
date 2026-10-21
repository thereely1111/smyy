-- M5：工具人工确认开关（internal 工具被调用时触发 REQUIRE_USER_CONFIRM）

ALTER TABLE tools
    ADD COLUMN ask_confirmation BOOLEAN NOT NULL DEFAULT FALSE;

-- 数据库查询（只读 SQL）改为需要人工确认，作为 REQUIRE_USER_CONFIRM 演示入口
UPDATE tools
SET ask_confirmation = TRUE
WHERE name = '数据库查询';
