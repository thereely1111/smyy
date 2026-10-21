-- 先删除种子用户拥有的知识库（子表 knowledge_base_chunks / documents / grants 均有 ON DELETE CASCADE）
DELETE FROM knowledge_bases WHERE owner_user_id IN (SELECT id FROM users WHERE username = 'admin' AND auth_type = 'password');

DELETE FROM users WHERE username = 'admin' AND auth_type = 'password';