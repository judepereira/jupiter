ALTER TABLE app_state ADD COLUMN anthropic_access_token TEXT NULL;
ALTER TABLE app_state ADD COLUMN anthropic_refresh_token TEXT NULL;
ALTER TABLE app_state ADD COLUMN anthropic_expires_at TIMESTAMP NULL;
ALTER TABLE app_state ADD COLUMN anthropic_scopes TEXT NULL;
ALTER TABLE app_state ADD COLUMN anthropic_account_json TEXT NULL;
ALTER TABLE app_state ADD COLUMN favourite_model_ids_json TEXT NULL;
ALTER TABLE app_state ADD COLUMN openai_initialized BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_state ADD COLUMN anthropic_initialized BOOLEAN NOT NULL DEFAULT FALSE;
