ALTER TABLE workspaces ADD COLUMN normalized_path VARCHAR(1024);

UPDATE workspaces
SET normalized_path = (
    SELECT p.normalized_path
    FROM projects p
    WHERE p.id = workspaces.project_id
);

ALTER TABLE workspaces ALTER COLUMN normalized_path SET NOT NULL;
