ALTER TABLE app_state ADD COLUMN assistant_completed_hook_script TEXT NULL;
ALTER TABLE app_state ADD COLUMN assistant_errored_hook_script TEXT NULL;
ALTER TABLE app_state ADD COLUMN subagent_completed_hook_script TEXT NULL;
ALTER TABLE app_state ADD COLUMN lifecycle_hook_timeout_seconds INTEGER NULL;
