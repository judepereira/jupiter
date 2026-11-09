CREATE TABLE inbound_mcp_credentials (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,
    token_ciphertext TEXT NOT NULL,
    all_projects INTEGER NOT NULL,
    project_ids_json TEXT NOT NULL,
    allowed_tools_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    rotated_at TEXT,
    revoked_at TEXT
);

CREATE INDEX idx_inbound_mcp_credentials_token_hash ON inbound_mcp_credentials(token_hash);

CREATE TABLE app_settings (
    setting_key TEXT PRIMARY KEY,
    setting_value TEXT NOT NULL
);
