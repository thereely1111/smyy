-- 「系统 / 模型凭证」管理入口定位为平台级（2026-09-19 需求确认）：
-- 该模块创建的凭证一律平台共享。scope 功能上线前创建的存量凭证
-- （默认 USER，如「百炼」）统一升级为 PLATFORM，供全员在对话选模型中使用。
UPDATE model_credentials SET scope = 'PLATFORM' WHERE scope = 'USER';
