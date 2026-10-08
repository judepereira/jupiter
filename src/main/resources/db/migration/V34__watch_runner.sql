ALTER TABLE watch_runs ADD COLUMN chat_id TEXT;

CREATE INDEX idx_watch_enablements_enabled ON watch_enablements(enabled);

UPDATE watch_runs SET status='INTERRUPTED', finished_at=CURRENT_TIMESTAMP,
    error=COALESCE(error, 'Application restarted while evaluator was running')
WHERE status='RUNNING';
