-- 凭证级模型启用清单（2026-09-22 模型核验流程）：
-- NULL = 未设置 = 该凭证目录下全部模型可用（向后兼容存量凭证）；
-- 非空 jsonb 数组 = 仅列出的模型名在各下拉（对话选模型等）可见。
-- 厂商核验返回的模型清单存 data.vendor_models（明文，非密钥），此处只存用户筛选后的启用集。
ALTER TABLE model_credentials ADD COLUMN IF NOT EXISTS enabled_models jsonb;
