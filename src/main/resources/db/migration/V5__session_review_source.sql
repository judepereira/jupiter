ALTER TABLE sessions ADD review_source VARCHAR(16) NOT NULL DEFAULT 'SESSION';
ALTER TABLE sessions ADD selected_git_changed_file_path VARCHAR(1024) NULL;
