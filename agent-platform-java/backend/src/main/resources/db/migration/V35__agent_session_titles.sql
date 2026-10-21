ALTER TABLE agent_session_owners ADD COLUMN IF NOT EXISTS title VARCHAR(255);
ALTER TABLE agent_session_owners ADD COLUMN IF NOT EXISTS title_source VARCHAR(16);
