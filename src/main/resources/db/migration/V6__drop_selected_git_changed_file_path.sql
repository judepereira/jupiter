PRAGMA foreign_keys = OFF;
PRAGMA legacy_alter_table = ON;

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
    CONSTRAINT fk_sessions_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT uk_sessions_workspace_position UNIQUE (workspace_id, position)
);

INSERT INTO sessions (id, workspace_id, name, position, review_panel_open, selected_changed_file_id, created_at, last_opened_at, review_source)
SELECT id, workspace_id, name, position, review_panel_open, selected_changed_file_id, created_at, last_opened_at, review_source
FROM sessions_old;

DROP TABLE sessions_old;

CREATE INDEX idx_sessions_workspace_id ON sessions (workspace_id);

PRAGMA legacy_alter_table = OFF;
PRAGMA foreign_keys = ON;
