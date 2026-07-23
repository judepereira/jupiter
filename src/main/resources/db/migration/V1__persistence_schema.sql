CREATE TABLE projects (
    id INTEGER PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    normalized_path VARCHAR(1024) NOT NULL,
    display_order BIGINT NOT NULL,
    closed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_opened_at TIMESTAMP NULL,
    CONSTRAINT uk_projects_normalized_path UNIQUE (normalized_path)
);

CREATE TABLE workspaces (
    id INTEGER PRIMARY KEY,
    project_id INTEGER NOT NULL,
    name VARCHAR(255) NOT NULL,
    position BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_opened_at TIMESTAMP NULL,
    CONSTRAINT fk_workspaces_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT uk_workspaces_project_position UNIQUE (project_id, position)
);

CREATE TABLE sessions (
    id INTEGER PRIMARY KEY,
    workspace_id INTEGER NOT NULL,
    name VARCHAR(255) NOT NULL,
    position BIGINT NOT NULL,
    review_panel_open INTEGER NOT NULL DEFAULT 0,
    selected_changed_file_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_opened_at TIMESTAMP NULL,
    CONSTRAINT fk_sessions_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT uk_sessions_workspace_position UNIQUE (workspace_id, position)
);

CREATE TABLE conversation_messages (
    id INTEGER PRIMARY KEY,
    session_id INTEGER NOT NULL,
    public_id VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL,
    turn_id BIGINT NOT NULL,
    sequence BIGINT NOT NULL,
    content TEXT NOT NULL,
    tool_call_id VARCHAR(255) NULL,
    tool_calls_json TEXT NULL,
    show_in_chat INTEGER NOT NULL DEFAULT 1,
    include_in_model INTEGER NOT NULL DEFAULT 1,
    pending INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_conversation_messages_session FOREIGN KEY (session_id) REFERENCES sessions (id),
    CONSTRAINT uk_conversation_messages_public_id UNIQUE (public_id),
    CONSTRAINT uk_conversation_messages_session_sequence UNIQUE (session_id, sequence)
);

CREATE TABLE tool_call_traces (
    id INTEGER PRIMARY KEY,
    session_id INTEGER NOT NULL,
    assistant_message_id INTEGER NOT NULL,
    sequence BIGINT NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    success INTEGER NOT NULL,
    args_json TEXT NULL,
    text_summary TEXT NULL,
    machine_summary_json TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tool_call_traces_session FOREIGN KEY (session_id) REFERENCES sessions (id),
    CONSTRAINT fk_tool_call_traces_assistant_message FOREIGN KEY (assistant_message_id) REFERENCES conversation_messages (id),
    CONSTRAINT uk_tool_call_traces_session_sequence UNIQUE (session_id, sequence)
);

CREATE TABLE changed_files (
    id INTEGER PRIMARY KEY,
    session_id INTEGER NOT NULL,
    path VARCHAR(1024) NOT NULL,
    diff TEXT NOT NULL,
    position BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_changed_files_session FOREIGN KEY (session_id) REFERENCES sessions (id),
    CONSTRAINT uk_changed_files_session_position UNIQUE (session_id, position)
);

CREATE TABLE app_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    active_project_id INTEGER NULL,
    active_workspace_id INTEGER NULL,
    active_session_id INTEGER NULL,
    CONSTRAINT fk_app_state_project FOREIGN KEY (active_project_id) REFERENCES projects (id),
    CONSTRAINT fk_app_state_workspace FOREIGN KEY (active_workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_app_state_session FOREIGN KEY (active_session_id) REFERENCES sessions (id)
);

INSERT INTO app_state (id, active_project_id, active_workspace_id, active_session_id)
VALUES (1, NULL, NULL, NULL);

CREATE INDEX idx_workspaces_project_id ON workspaces (project_id);
CREATE INDEX idx_sessions_workspace_id ON sessions (workspace_id);
CREATE INDEX idx_conversation_messages_session_id ON conversation_messages (session_id);
CREATE INDEX idx_tool_call_traces_session_id ON tool_call_traces (session_id);
CREATE INDEX idx_tool_call_traces_assistant_message_id ON tool_call_traces (assistant_message_id);
CREATE INDEX idx_changed_files_session_id ON changed_files (session_id);
