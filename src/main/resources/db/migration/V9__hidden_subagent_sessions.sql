ALTER TABLE sessions ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE sessions ADD COLUMN parent_session_id BIGINT NULL;
ALTER TABLE sessions ADD COLUMN parent_tool_call_id VARCHAR(255) NULL;
ALTER TABLE sessions ADD COLUMN subagent_agent_id VARCHAR(255) NULL;
ALTER TABLE sessions ADD COLUMN subagent_agent_name VARCHAR(255) NULL;

ALTER TABLE sessions ADD CONSTRAINT fk_sessions_parent FOREIGN KEY (parent_session_id) REFERENCES sessions (id) ON DELETE CASCADE;
ALTER TABLE sessions ADD CONSTRAINT ck_sessions_hidden_parent CHECK ((hidden = FALSE AND parent_session_id IS NULL) OR (hidden = TRUE AND parent_session_id IS NOT NULL));

CREATE INDEX idx_sessions_parent_session_id ON sessions (parent_session_id);
