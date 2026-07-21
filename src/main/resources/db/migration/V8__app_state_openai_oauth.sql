ALTER TABLE app_state ADD COLUMN openai_access_token TEXT NULL;
ALTER TABLE app_state ADD COLUMN openai_refresh_token TEXT NULL;
ALTER TABLE app_state ADD COLUMN openai_id_token TEXT NULL;
ALTER TABLE app_state ADD COLUMN openai_account_id VARCHAR(255) NULL;
ALTER TABLE app_state ADD COLUMN openai_expires_at TIMESTAMP NULL;
