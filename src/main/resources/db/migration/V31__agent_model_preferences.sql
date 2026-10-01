CREATE TABLE agent_model_preferences (
    agent_id TEXT PRIMARY KEY NOT NULL,
    model_id TEXT,
    thinking_level TEXT,
    CHECK (model_id IS NOT NULL OR thinking_level IS NOT NULL)
);
