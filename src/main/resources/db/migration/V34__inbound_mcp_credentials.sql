CREATE TABLE inbound_mcp_credentials (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,
    all_projects INTEGER NOT NULL,
    project_ids_json TEXT NOT NULL,
    allowed_tools_json TEXT NOT NULL,
    created_at TEXT NOT NULL
);
