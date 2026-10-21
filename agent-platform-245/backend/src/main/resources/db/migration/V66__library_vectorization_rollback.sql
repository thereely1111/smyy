-- 撤销资料库向量化：删除 V64/V65 建立的两张表。
--
-- 决策：资料库文件不做向量化、不分块，检索改由模型经 library_read 工具
-- 按引用串（library://node/<id>）回读正文完成——与知识库 text 类型
-- 的「grep/cat 直读」路径同理。
--
-- ⚠️ 不能改 V64/V65：已进 Flyway history（checksum 校验），只能叠加 V66。
--
-- ⚠️ 本脚本幂等可重入：DROP 用 IF EXISTS；分块表对索引表有
-- ON DELETE CASCADE 外键依赖，先删子表再删父表（DROP 顺序不敏感，
-- 但保留显式顺序以免个别环境外键行为差异）。

DROP TABLE IF EXISTS library_chunks;
DROP TABLE IF EXISTS library_chunk_index;
