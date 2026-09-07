CREATE TABLE encryption_metadata (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    verifier TEXT NOT NULL,
    format_version INTEGER NOT NULL,
    migration_complete INTEGER NOT NULL DEFAULT 0 CHECK (migration_complete IN (0, 1))
);

ALTER TABLE projects ADD COLUMN normalized_path_blind_index VARCHAR(64);
CREATE UNIQUE INDEX uk_projects_normalized_path_blind_index ON projects (normalized_path_blind_index);
