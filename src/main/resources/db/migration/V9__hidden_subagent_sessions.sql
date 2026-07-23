PRAGMA foreign_keys = OFF;
PRAGMA legacy_alter_table = ON;

ALTER TABLE sessions ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sessions ADD COLUMN parent_session_id BIGINT NULL;
ALTER TABLE sessions ADD COLUMN parent_tool_call_id VARCHAR(255) NULL;
ALTER TABLE sessions ADD COLUMN subagent_agent_id VARCHAR(255) NULL;
ALTER TABLE sessions ADD COLUMN subagent_agent_name VARCHAR(255) NULL;

ALTER TABLE sessions RENAME TO sessions_old;

CREATE TABLE sessions (
    id INTEGER PRIMARY KEY,
    workspace_id INTEGER NOT NULL,
    name VARCHAR(255) NOT NULL,
    position BIGINT NOT NULL,
    review_panel_open INTEGER NOT NULL DEFAULT 0,
    selected_changed_file_id INTEGER NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_opened_at TIMESTAMP NULL,
    review_source VARCHAR(16) NOT NULL DEFAULT 'SESSION',
    hidden INTEGER NOT NULL DEFAULT 0,
    parent_session_id INTEGER NULL,
    parent_tool_call_id VARCHAR(255) NULL,
    subagent_agent_id VARCHAR(255) NULL,
    subagent_agent_name VARCHAR(255) NULL,
    CONSTRAINT fk_sessions_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_sessions_parent FOREIGN KEY (parent_session_id) REFERENCES sessions (id) ON DELETE CASCADE,
    CONSTRAINT ck_sessions_hidden_parent CHECK ((hidden = 0 AND parent_session_id IS NULL) OR (hidden = 1 AND parent_session_id IS NOT NULL)),
    CONSTRAINT uk_sessions_workspace_position UNIQUE (workspace_id, position)
);

INSERT INTO sessions (id, workspace_id, name, position, review_panel_open, selected_changed_file_id, created_at, last_opened_at, review_source, hidden, parent_session_id, parent_tool_call_id, subagent_agent_id, subagent_agent_name)
SELECT id, workspace_id, name, position, review_panel_open, selected_changed_file_id, created_at, last_opened_at, review_source, hidden, parent_session_id, parent_tool_call_id, subagent_agent_id, subagent_agent_name
FROM sessions_old;

DROP TABLE sessions_old;

CREATE INDEX idx_sessions_parent_session_id ON sessions (parent_session_id);
CREATE INDEX idx_sessions_workspace_id ON sessions (workspace_id);

PRAGMA legacy_alter_table = OFF;
PRAGMA foreign_keys = ON;
