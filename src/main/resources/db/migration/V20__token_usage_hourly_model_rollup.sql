PRAGMA foreign_keys = OFF;
PRAGMA legacy_alter_table = ON;

ALTER TABLE token_usage_hourly RENAME TO token_usage_hourly_old;

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
    request_count BIGINT NOT NULL,
    input_token_count BIGINT NULL,
    output_token_count BIGINT NULL,
    total_token_count BIGINT NULL,
    cached_input_token_count BIGINT NULL,
    cache_write_token_count BIGINT NULL,
    reasoning_token_count BIGINT NULL,
    last_occurred_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_token_usage_hourly_key UNIQUE (session_usage_key, hour_start_utc, model_key)
);

INSERT INTO token_usage_hourly (
    session_usage_key, session_id_snapshot, workspace_id_snapshot, project_id_snapshot,
    session_name_snapshot, workspace_name_snapshot, project_name_snapshot, workspace_path_snapshot, project_path_snapshot,
    hour_start_utc, model_key, request_count, input_token_count, output_token_count, total_token_count,
    cached_input_token_count, cache_write_token_count, reasoning_token_count, last_occurred_at, created_at
)
SELECT session_usage_key, MIN(session_id_snapshot), MIN(workspace_id_snapshot), MIN(project_id_snapshot),
       MIN(session_name_snapshot), MIN(workspace_name_snapshot), MIN(project_name_snapshot), MIN(workspace_path_snapshot), MIN(project_path_snapshot),
       hour_start_utc, model_key, SUM(request_count), SUM(input_token_count), SUM(output_token_count), SUM(total_token_count),
       SUM(cached_input_token_count), SUM(cache_write_token_count), SUM(reasoning_token_count), MAX(last_occurred_at), MIN(created_at)
FROM token_usage_hourly_old
GROUP BY session_usage_key, hour_start_utc, model_key;

DROP TABLE token_usage_hourly_old;

CREATE INDEX idx_token_usage_hourly_session_hour ON token_usage_hourly (session_usage_key, hour_start_utc);
CREATE INDEX idx_token_usage_hourly_hour_start ON token_usage_hourly (hour_start_utc);

PRAGMA legacy_alter_table = OFF;
PRAGMA foreign_keys = ON;
