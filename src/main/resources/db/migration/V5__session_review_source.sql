ALTER TABLE sessions ADD COLUMN review_source VARCHAR(16) NOT NULL DEFAULT 'SESSION';
ALTER TABLE sessions ADD COLUMN selected_git_changed_file_path VARCHAR(1024) NULL;
