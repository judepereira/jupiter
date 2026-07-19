ALTER TABLE conversation_messages ADD COLUMN agent_id VARCHAR(64) NULL;
ALTER TABLE conversation_messages ADD COLUMN agent_name VARCHAR(255) NULL;
ALTER TABLE conversation_messages ADD COLUMN model_id VARCHAR(255) NULL;
ALTER TABLE conversation_messages ADD COLUMN thinking_level VARCHAR(32) NULL;
