ALTER TABLE conversation_messages ADD COLUMN watch_generated INTEGER NOT NULL DEFAULT 0;

CREATE TABLE watch_definitions (
    id INTEGER PRIMARY KEY,
    project_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    prompt TEXT NOT NULL,
    interval_seconds INTEGER NOT NULL CHECK (interval_seconds BETWEEN 60 AND 86400),
    evaluator_agent_id TEXT NOT NULL,
    action_agent_id TEXT NOT NULL,
    action_command_id TEXT NOT NULL,
    config_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
);
CREATE INDEX idx_watch_definitions_project ON watch_definitions(project_id);

CREATE TABLE watch_enablements (
    watch_id INTEGER NOT NULL,
    session_id INTEGER NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    activity_generation INTEGER NOT NULL DEFAULT 0,
    last_evaluation_started_at TIMESTAMP NULL,
    invalidated_at TIMESTAMP NULL,
    PRIMARY KEY (watch_id, session_id),
    FOREIGN KEY (watch_id) REFERENCES watch_definitions(id) ON DELETE CASCADE,
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
);
CREATE INDEX idx_watch_enablements_session ON watch_enablements(session_id);

ALTER TABLE sessions ADD COLUMN watch_panel_open INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sessions ADD COLUMN watch_panel_height INTEGER NOT NULL DEFAULT 320;

CREATE TABLE watch_runs (
    id INTEGER PRIMARY KEY,
    watch_id INTEGER NULL,
    session_id INTEGER NOT NULL,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP NULL,
    status TEXT NOT NULL,
    actionable INTEGER NULL,
    dispatched INTEGER NOT NULL DEFAULT 0,
    config_version INTEGER NOT NULL,
    prompt TEXT NOT NULL,
    evaluator_agent_id TEXT NOT NULL,
    action_agent_id TEXT NOT NULL,
    action_command_id TEXT NOT NULL,
    output TEXT NULL,
    error TEXT NULL,
    watch_name TEXT NOT NULL DEFAULT 'Deleted watch',
    chat_id TEXT NULL,
    FOREIGN KEY (watch_id) REFERENCES watch_definitions(id) ON DELETE SET NULL,
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
);
CREATE INDEX idx_watch_runs_session_started ON watch_runs(session_id, started_at DESC);
CREATE INDEX idx_watch_enablements_enabled ON watch_enablements(enabled);
