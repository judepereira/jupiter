CREATE TABLE workspace_auto_git_update_failure_notifications (
    workspace_id INTEGER NOT NULL,
    session_id INTEGER NOT NULL,
    delivered_at TIMESTAMP NOT NULL,
    PRIMARY KEY (workspace_id, session_id),
    CONSTRAINT fk_workspace_auto_git_update_failure_notifications_workspace
        FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_workspace_auto_git_update_failure_notifications_session
        FOREIGN KEY (session_id) REFERENCES sessions (id) ON DELETE CASCADE
);
