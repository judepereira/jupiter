PRAGMA foreign_keys = OFF;

ALTER TABLE watch_runs RENAME TO watch_runs_old;

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

INSERT INTO watch_runs (id, watch_id, session_id, started_at, finished_at, status, actionable, dispatched,
        config_version, prompt, evaluator_agent_id, action_agent_id, action_command_id, output, error, watch_name,
        chat_id)
SELECT id, watch_id, session_id, started_at, finished_at, status, actionable, dispatched, config_version, prompt,
        evaluator_agent_id, action_agent_id, action_command_id, output, error, watch_name, chat_id
FROM watch_runs_old;

DROP TABLE watch_runs_old;
CREATE INDEX idx_watch_runs_session_started ON watch_runs(session_id, started_at DESC);

PRAGMA foreign_keys = ON;
