PRAGMA foreign_keys = OFF;
PRAGMA legacy_alter_table = ON;

ALTER TABLE workspaces ADD COLUMN normalized_path VARCHAR(1024);

UPDATE workspaces
SET normalized_path = (
    SELECT p.normalized_path
    FROM projects p
    WHERE p.id = workspaces.project_id
);

ALTER TABLE workspaces RENAME TO workspaces_old;

CREATE TABLE workspaces (
    id INTEGER PRIMARY KEY,
    project_id INTEGER NOT NULL,
    name VARCHAR(255) NOT NULL,
    position BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_opened_at TIMESTAMP NULL,
    normalized_path VARCHAR(1024) NOT NULL,
    CONSTRAINT fk_workspaces_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT uk_workspaces_project_position UNIQUE (project_id, position)
);

INSERT INTO workspaces (id, project_id, name, position, created_at, last_opened_at, normalized_path)
SELECT id, project_id, name, position, created_at, last_opened_at, normalized_path
FROM workspaces_old;

DROP TABLE workspaces_old;

CREATE INDEX idx_workspaces_project_id ON workspaces (project_id);

PRAGMA legacy_alter_table = OFF;
PRAGMA foreign_keys = ON;
