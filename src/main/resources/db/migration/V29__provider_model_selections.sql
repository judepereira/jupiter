CREATE TABLE provider_model_selections (
    provider TEXT NOT NULL,
    model_id TEXT NOT NULL,
    PRIMARY KEY (provider, model_id)
);

DROP INDEX IF EXISTS app_state_favourite_model_ids_json_blind_index;
ALTER TABLE app_state DROP COLUMN favourite_model_ids_json;
ALTER TABLE app_state DROP COLUMN openai_initialized;
ALTER TABLE app_state DROP COLUMN anthropic_initialized;
