package com.judepereira.jupiter.security;

import java.sql.Connection;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class EncryptionMigrationService {
    static final int BATCH_SIZE = 100;

    private static final int CURRENT_FORMAT_VERSION = 1;
    private static final long METADATA_ID = 1L;
    private static final String VERIFIER_AAD = "encryption_metadata.verifier";
    private static final String VERIFIER = "Jupiter encryption verifier v1";
    private static final List<ColumnDescriptor> COLUMNS = Stream.of(
            columns("projects", "name", "normalized_path"),
            columns("projects", "workspace_init_commands", "environment_variables", "command_environment_allowlist"),
            columns("workspaces", "name", "normalized_path"),
            columns("sessions", "name", "chat_draft", "subagent_agent_id", "subagent_agent_name"),
            columns("conversation_messages", "content", "tool_calls_json", "agent_id", "agent_name", "model_id", "thinking_level"),
            columns("tool_call_traces", "args_json", "text_summary", "machine_summary_json"),
            columns("changed_files", "path", "diff"),
            columns("app_state", "openai_access_token", "openai_refresh_token", "openai_id_token", "openai_account_id",
                    "assistant_completed_hook_script", "assistant_errored_hook_script", "subagent_completed_hook_script"),
            columns("mcp_servers", "name", "url", "headers_json"),
            columns("token_usage_facts", "session_name_snapshot", "workspace_name_snapshot", "project_name_snapshot",
                    "workspace_path_snapshot", "project_path_snapshot", "response_id", "response_model_id", "finish_reason",
                    "provider_metadata_json"),
            columns("token_usage_hourly", "session_name_snapshot", "workspace_name_snapshot", "project_name_snapshot",
                    "workspace_path_snapshot", "project_path_snapshot"))
            .flatMap(List::stream)
            .toList();

    private final TextEncryptor crypto;

    public void run(Connection connection) {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        boolean complete = executeWithBusyRetry(() -> initializeVerifier(jdbc, transactions));
        if (complete) {
            return;
        }

        for (ColumnDescriptor descriptor : COLUMNS) {
            migrate(descriptor, jdbc, transactions);
        }
        executeWithBusyRetry(() -> markComplete(jdbc, transactions));
    }

    private static List<ColumnDescriptor> columns(String table, String... names) {
        return Arrays.stream(names).map(name -> new ColumnDescriptor(table, name)).toList();
    }

    private boolean initializeVerifier(JdbcTemplate jdbc, TransactionTemplate transactions) {
        return transactions.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT verifier, format_version, migration_complete FROM encryption_metadata WHERE id = ?",
                    METADATA_ID);
            if (rows.isEmpty()) {
                jdbc.update("INSERT INTO encryption_metadata (id, verifier, format_version) VALUES (?, ?, ?)",
                        METADATA_ID, crypto.encrypt(VERIFIER, VERIFIER_AAD), CURRENT_FORMAT_VERSION);
                return false;
            }

            Map<String, Object> row = rows.getFirst();
            int version = ((Number) row.get("format_version")).intValue();
            if (version != CURRENT_FORMAT_VERSION) {
                throw new IllegalStateException("Unsupported encryption metadata format version: " + version);
            }
            try {
                if (!VERIFIER.equals(crypto.decrypt((String) row.get("verifier"), VERIFIER_AAD))) {
                    throw wrongKey();
                }
            } catch (TextEncryptor.EncryptionException exception) {
                throw wrongKey();
            }
            return ((Number) row.get("migration_complete")).intValue() == 1;
        });
    }

    private Void markComplete(JdbcTemplate jdbc, TransactionTemplate transactions) {
        transactions.executeWithoutResult(status ->
                jdbc.update("UPDATE encryption_metadata SET migration_complete = 1 WHERE id = ?", METADATA_ID));
        return null;
    }

    private void migrate(ColumnDescriptor descriptor, JdbcTemplate jdbc, TransactionTemplate transactions) {
        long lastId = 0;
        while (true) {
            long position = lastId;
            BatchResult result = executeWithBusyRetry(
                    () -> migrateBatch(descriptor, position, jdbc, transactions));
            if (result.rows() == 0) {
                return;
            }
            lastId = result.lastId();
        }
    }

    private BatchResult migrateBatch(ColumnDescriptor descriptor, long lastId, JdbcTemplate jdbc,
                                     TransactionTemplate transactions) {
        return transactions.execute(status -> {
            String sql = "SELECT id, \"" + descriptor.column() + "\" AS value FROM \""
                    + descriptor.table() + "\" WHERE id > ? ORDER BY id LIMIT ?";
            List<Map<String, Object>> rows = jdbc.queryForList(sql, lastId, BATCH_SIZE);
            long newestId = lastId;
            for (Map<String, Object> row : rows) {
                long id = ((Number) row.get("id")).longValue();
                newestId = id;
                migrateValue(descriptor, id, row.get("value"), jdbc);
            }
            return new BatchResult(rows.size(), newestId);
        });
    }

    private void migrateValue(ColumnDescriptor descriptor, long id, Object raw, JdbcTemplate jdbc) {
        if (raw == null) {
            return;
        }

        String value = (String) raw;
        String aad = descriptor.table() + "." + descriptor.column();
        String plaintext;
        try {
            plaintext = TextEncryptor.isEncrypted(value) ? crypto.decrypt(value, aad) : value;
        } catch (RuntimeException exception) {
            throw applicationDataError(descriptor, id, exception);
        }

        try {
            if (!TextEncryptor.isEncrypted(value)) {
                jdbc.update("UPDATE \"" + descriptor.table() + "\" SET \"" + descriptor.column()
                                + "\" = ? WHERE id = ?",
                        crypto.encrypt(plaintext, aad), id);
            }
            if (descriptor.isProjectPath()) {
                updateProjectBlindIndex(id, plaintext, jdbc);
            }
        } catch (DataAccessException | TextEncryptor.EncryptionException exception) {
            throw applicationDataError(descriptor, id, exception);
        }
    }

    private void updateProjectBlindIndex(long id, String plaintext, JdbcTemplate jdbc) {
        String index = crypto.blindIndex(plaintext, "projects.normalized_path");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT normalized_path_blind_index FROM projects WHERE id = ?", id);
        Object existing = row.get("normalized_path_blind_index");
        if (existing != null && !index.equals(existing)) {
            throw new IllegalStateException("Invalid blind index for projects.normalized_path row " + id);
        }
        if (existing == null) {
            jdbc.update("UPDATE projects SET normalized_path_blind_index = ? WHERE id = ?", index, id);
        }
    }

    private IllegalStateException applicationDataError(ColumnDescriptor descriptor, long id, RuntimeException cause) {
        return new IllegalStateException("Unable to migrate " + descriptor.table() + "." + descriptor.column()
                + " row " + id, cause);
    }

    private IllegalStateException wrongKey() {
        return new IllegalStateException("JUPITER_ENCRYPTION_KEY does not match this database");
    }

    private <T> T executeWithBusyRetry(Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException exception) {
                if (attempt >= 10 || !isBusy(exception)) {
                    throw exception;
                }
                try {
                    Thread.sleep(attempt * 100L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw exception;
                }
            }
        }
    }

    private boolean isBusy(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains("SQLITE_BUSY")) {
                return true;
            }
        }
        return false;
    }

    private record ColumnDescriptor(String table, String column) {
        private boolean isProjectPath() {
            return table.equals("projects") && column.equals("normalized_path");
        }
    }

    private record BatchResult(int rows, long lastId) {
    }
}
