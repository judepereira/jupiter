CREATE TABLE mcp_servers (
    id INTEGER PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    headers_json TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE project_mcp_servers (
    mcp_server_id INTEGER NOT NULL,
    project_id INTEGER NOT NULL,
    CONSTRAINT fk_project_mcp_servers_server FOREIGN KEY (mcp_server_id) REFERENCES mcp_servers (id) ON DELETE CASCADE,
    CONSTRAINT fk_project_mcp_servers_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT uk_project_mcp_servers_server_project UNIQUE (mcp_server_id, project_id)
);

CREATE INDEX idx_project_mcp_servers_project_id ON project_mcp_servers (project_id);
CREATE INDEX idx_project_mcp_servers_mcp_server_id ON project_mcp_servers (mcp_server_id);
