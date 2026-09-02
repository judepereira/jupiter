ALTER TABLE sessions ADD COLUMN session_usage_key VARCHAR(64) NULL;

UPDATE sessions
SET session_usage_key = lower(hex(randomblob(16)))
WHERE session_usage_key IS NULL;

CREATE UNIQUE INDEX uk_sessions_session_usage_key ON sessions (session_usage_key);

CREATE TABLE token_usage_facts (
    id INTEGER PRIMARY KEY,
    session_usage_key VARCHAR(64) NOT NULL,
    session_id_snapshot BIGINT NOT NULL,
    workspace_id_snapshot BIGINT NOT NULL,
    project_id_snapshot BIGINT NOT NULL,
    session_name_snapshot VARCHAR(255) NOT NULL,
    workspace_name_snapshot VARCHAR(255) NOT NULL,
    project_name_snapshot VARCHAR(255) NOT NULL,
    workspace_path_snapshot VARCHAR(1024) NOT NULL,
    project_path_snapshot VARCHAR(1024) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    hour_start_utc TIMESTAMP NOT NULL,
    model_key VARCHAR(255) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    input_token_count INTEGER NULL,
    output_token_count INTEGER NULL,
    total_token_count INTEGER NULL,
    cached_input_token_count INTEGER NULL,
    cache_write_token_count INTEGER NULL,
    reasoning_token_count INTEGER NULL,
    response_id VARCHAR(255) NULL,
    response_model_id VARCHAR(255) NULL,
    finish_reason VARCHAR(64) NULL,
    provider_metadata_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_token_usage_facts_session_hour ON token_usage_facts (session_usage_key, hour_start_utc);
CREATE INDEX idx_token_usage_facts_occurred_at ON token_usage_facts (occurred_at);

CREATE TABLE token_usage_hourly (
    id INTEGER PRIMARY KEY,
    session_usage_key VARCHAR(64) NOT NULL,
    session_id_snapshot BIGINT NOT NULL,
    workspace_id_snapshot BIGINT NOT NULL,
    project_id_snapshot BIGINT NOT NULL,
    session_name_snapshot VARCHAR(255) NOT NULL,
    workspace_name_snapshot VARCHAR(255) NOT NULL,
    project_name_snapshot VARCHAR(255) NOT NULL,
    workspace_path_snapshot VARCHAR(1024) NOT NULL,
    project_path_snapshot VARCHAR(1024) NOT NULL,
    hour_start_utc TIMESTAMP NOT NULL,
    model_key VARCHAR(255) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    request_count BIGINT NOT NULL,
    input_token_count BIGINT NULL,
    output_token_count BIGINT NULL,
    total_token_count BIGINT NULL,
    cached_input_token_count BIGINT NULL,
    cache_write_token_count BIGINT NULL,
    reasoning_token_count BIGINT NULL,
    last_occurred_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_token_usage_hourly_key UNIQUE (session_usage_key, hour_start_utc, model_key, operation)
);

CREATE INDEX idx_token_usage_hourly_session_hour ON token_usage_hourly (session_usage_key, hour_start_utc);
