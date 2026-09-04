ALTER TABLE app_state ADD COLUMN auto_git_update INTEGER NOT NULL DEFAULT 1;

CREATE TABLE workspace_auto_git_update_state (
    workspace_id INTEGER PRIMARY KEY,
    failure_episode_active INTEGER NOT NULL DEFAULT 0,
    failure_started_at TIMESTAMP NULL,
    last_success_at TIMESTAMP NULL,
    CONSTRAINT fk_workspace_auto_git_update_state_workspace
        FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);

INSERT INTO workspace_auto_git_update_state (workspace_id)
SELECT id FROM workspaces;
