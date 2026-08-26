PRAGMA foreign_keys = OFF;
PRAGMA legacy_alter_table = ON;

ALTER TABLE tool_call_traces RENAME TO tool_call_traces_old;

CREATE TABLE tool_call_traces (
    id INTEGER PRIMARY KEY,
    session_id INTEGER NOT NULL,
    assistant_message_id INTEGER NOT NULL,
    sequence BIGINT NOT NULL,
    tool_call_id VARCHAR(255) NULL,
    tool_name VARCHAR(255) NOT NULL,
    success INTEGER NULL,
    args_json TEXT NULL,
    text_summary TEXT NULL,
    machine_summary_json TEXT NULL,
    completed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tool_call_traces_session FOREIGN KEY (session_id) REFERENCES sessions (id),
    CONSTRAINT fk_tool_call_traces_assistant_message FOREIGN KEY (assistant_message_id) REFERENCES conversation_messages (id),
    CONSTRAINT uk_tool_call_traces_session_sequence UNIQUE (session_id, sequence)
);

INSERT INTO tool_call_traces (
    id,
    session_id,
    assistant_message_id,
    sequence,
    tool_call_id,
    tool_name,
    success,
    args_json,
    text_summary,
    machine_summary_json,
    completed_at,
    created_at
)
SELECT
    id,
    session_id,
    assistant_message_id,
    sequence,
    tool_call_id,
    tool_name,
    success,
    args_json,
    text_summary,
    machine_summary_json,
    created_at,
    created_at
FROM tool_call_traces_old;

DROP TABLE tool_call_traces_old;

CREATE INDEX idx_tool_call_traces_session_id ON tool_call_traces (session_id);
CREATE INDEX idx_tool_call_traces_assistant_message_id ON tool_call_traces (assistant_message_id);
CREATE UNIQUE INDEX idx_tool_call_traces_session_tool_call_id ON tool_call_traces (session_id, tool_call_id) WHERE tool_call_id IS NOT NULL;

PRAGMA legacy_alter_table = OFF;
PRAGMA foreign_keys = ON;
