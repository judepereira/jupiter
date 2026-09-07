package com.judepereira.jupiter.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.security.EncryptionKey;
import com.judepereira.jupiter.security.EncryptionMigrationCallback;
import com.judepereira.jupiter.security.EncryptionMigrationService;
import com.judepereira.jupiter.security.TextEncryptor;
import com.judepereira.jupiter.testsupport.SQLiteTestSupport;
import com.judepereira.jupiter.testsupport.TestEncryptionSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptionMigrationIntegrationTests {
    private static final String KEY = TestEncryptionSupport.KEY;

    @Test
    void callbackMigratesRowsAfterV26AndCompletesMigration() throws Exception {
        var dataSource = SQLiteTestSupport.fileBackedDataSource(
                Files.createTempDirectory("jupiter-encryption-callback-").resolve("db.sqlite"));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target("25").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO projects (id,name,normalized_path,display_order) VALUES (1,?,?,1)",
                "callback secret", "/tmp/callback-secret");

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .callbacks(new EncryptionMigrationCallback(new EncryptionMigrationService(TestEncryptionSupport.encryptor())))
                .load().migrate();

        assertThat(jdbc.queryForObject("SELECT migration_complete FROM encryption_metadata WHERE id=1", Integer.class))
                .isEqualTo(1);
        assertEncryptedAndHidden(jdbc, "projects", "name", "callback secret");
        assertEncryptedAndHidden(jdbc, "projects", "normalized_path", "/tmp/callback-secret");
    }

    @Test
    void convertsLegacyRowsAndRepositoryReadsPlaintext() throws Exception {
        TestDatabase db = database();
        seedRepresentativeRows(db.jdbc());

        run(db);

        assertThat(db.jdbc().queryForObject("SELECT migration_complete FROM encryption_metadata WHERE id=1", Integer.class)).isEqualTo(1);
        assertEncryptedAndHidden(db.jdbc(), "projects", "name", "project secret");
        assertEncryptedAndHidden(db.jdbc(), "projects", "normalized_path", "/tmp/project-secret");
        assertEncryptedAndHidden(db.jdbc(), "projects", "workspace_init_commands", "echo secret");
        assertEncryptedAndHidden(db.jdbc(), "conversation_messages", "content", "chat secret");
        assertEncryptedAndHidden(db.jdbc(), "tool_call_traces", "args_json", "{\"token\":\"trace secret\"}");
        assertEncryptedAndHidden(db.jdbc(), "app_state", "assistant_completed_hook_script", "hook secret");
        assertEncryptedAndHidden(db.jdbc(), "mcp_servers", "headers_json", "{\"Authorization\":\"oauth secret\"}");

        AppStateRepository repository = new AppStateRepository(new NamedParameterJdbcTemplate(db.dataSource()),
                TestEncryptionSupport.encryptor(), new ObjectMapper());
        assertThat(repository.findProjectByNormalizedPath("/tmp/project-secret")).get().extracting(AppStateRepository.ProjectRow::name).isEqualTo("project secret");
        assertThat(repository.findMcpServer(1L)).get().extracting(AppStateRepository.McpServerRow::headersJson).isEqualTo("{\"Authorization\":\"oauth secret\"}");
        assertThat(repository.loadLifecycleHookSettings().assistantCompletedScript()).isEqualTo("hook secret");
    }

    @Test
    void commitsMultipleBatchesAndOnlyCompletesAfterSuccess() throws Exception {
        TestDatabase db = database();
        for (int id = 1; id <= 101; id++) {
            db.jdbc().update("INSERT INTO projects (id,name,normalized_path,display_order) VALUES (?,?,?,?)",
                    id, "project-" + id, "/tmp/project-" + id, id);
        }
        run(db);
        assertThat(db.jdbc().queryForObject("SELECT COUNT(*) FROM projects WHERE name LIKE 'JUPITER-ENCRYPTED-V1-AES-256-GCM:%'", Integer.class)).isEqualTo(101);
        assertThat(db.jdbc().queryForObject("SELECT migration_complete FROM encryption_metadata WHERE id=1", Integer.class)).isEqualTo(1);
    }

    @Test
    void failedMigrationDoesNotMarkCompletionAndIdentifiesBadRowWithoutSecret() throws Exception {
        TestDatabase db = database();
        String secret = "do-not-leak-this-secret";
        db.jdbc().update("INSERT INTO projects (id,name,normalized_path,display_order) VALUES (1,?,?,1)",
                "JUPITER-ENCRYPTED-V1-AES-256-GCM:" + secret, "/tmp/bad");
        assertThatThrownBy(() -> run(db)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("projects.name row 1")
                .hasMessageNotContaining(secret);
        assertThat(db.jdbc().queryForObject("SELECT migration_complete FROM encryption_metadata WHERE id=1", Integer.class)).isEqualTo(0);
    }

    @Test
    void completedMigrationIsIdempotentAndWrongKeyIsRejected() throws Exception {
        TestDatabase db = database();
        db.jdbc().update("INSERT INTO projects (id,name,normalized_path,display_order) VALUES (1,?,?,1)", "name", "/tmp/idempotent");
        run(db);
        String ciphertext = db.jdbc().queryForObject("SELECT name FROM projects WHERE id=1", String.class);
        run(db);
        assertThat(db.jdbc().queryForObject("SELECT name FROM projects WHERE id=1", String.class)).isEqualTo(ciphertext);

        TextEncryptor wrong = new TextEncryptor(EncryptionKey.fromBase64(Base64.getEncoder().encodeToString(new byte[32])));
        assertThatThrownBy(() -> {
            try (var connection = db.dataSource().getConnection()) { migrationService(wrong).run(connection); }
            catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JUPITER_ENCRYPTION_KEY does not match this database");
    }

    @Test
    void blindIndexLookupAndDuplicateLogicalPathsUseUniqueIndex() throws Exception {
        TestDatabase db = database();
        db.jdbc().update("INSERT INTO projects (id,name,normalized_path,display_order) VALUES (1,?,?,1)", "one", "/tmp/same");
        run(db);
        AppStateRepository repository = new AppStateRepository(new NamedParameterJdbcTemplate(db.dataSource()), TestEncryptionSupport.encryptor(), new ObjectMapper());
        assertThat(repository.findProjectByNormalizedPath("/tmp/same")).get().extracting(AppStateRepository.ProjectRow::name).isEqualTo("one");
        assertThatThrownBy(() -> new NamedParameterJdbcTemplate(db.dataSource()).update(
                "INSERT INTO projects (id,name,normalized_path,normalized_path_blind_index,display_order) VALUES (2,:name,:path,:idx,2)",
                Map.of("name", TestEncryptionSupport.encrypt("projects", "name", "two"),
                        "path", TestEncryptionSupport.encrypt("projects", "normalized_path", "/tmp/other"),
                        "idx", TestEncryptionSupport.encryptor().blindIndex("/tmp/same", "projects.normalized_path"))))
                .hasMessageContaining("UNIQUE constraint failed: projects.normalized_path_blind_index");
    }

    private static TestDatabase database() throws Exception {
        var dataSource = SQLiteTestSupport.fileBackedDataSource(Files.createTempDirectory("jupiter-encryption-").resolve("db.sqlite"));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        return new TestDatabase(dataSource, new JdbcTemplate(dataSource));
    }

    private static void run(TestDatabase db) {
        try (var connection = db.dataSource().getConnection()) { migrationService(TestEncryptionSupport.encryptor()).run(connection); }
        catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
    }

    private static EncryptionMigrationService migrationService(TextEncryptor crypto) {
        return new EncryptionMigrationService(crypto);
    }

    private static void assertEncryptedAndHidden(JdbcTemplate jdbc, String table, String column, String plaintext) {
        String value = jdbc.queryForObject("SELECT \"" + column + "\" FROM \"" + table + "\" WHERE id=1", String.class);
        assertThat(value).startsWith("JUPITER-ENCRYPTED-V1-AES-256-GCM:").doesNotContain(plaintext);
    }

    private static void seedRepresentativeRows(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO projects (id,name,normalized_path,workspace_init_commands,environment_variables,command_environment_allowlist,display_order) VALUES (1,?,?,?,?,?,1)",
                "project secret", "/tmp/project-secret", "echo secret", "{\"SECRET\":\"settings secret\"}", "[\"PATH\"]");
        jdbc.update("INSERT INTO workspaces (id,project_id,name,normalized_path,position) VALUES (1,1,?,?,1)", "workspace secret", "/tmp/workspace-secret");
        jdbc.update("INSERT INTO sessions (id,workspace_id,name,chat_draft,position) VALUES (1,1,?,?,1)", "session secret", "draft secret");
        jdbc.update("INSERT INTO conversation_messages (id,session_id,public_id,role,turn_id,sequence,content,tool_calls_json) VALUES (1,1,'message-1','assistant',1,1,?,?)", "chat secret", "{\"tool\":\"json secret\"}");
        jdbc.update("INSERT INTO tool_call_traces (id,session_id,assistant_message_id,sequence,tool_name,success,args_json,text_summary,machine_summary_json) VALUES (1,1,1,1,'tool',1,?,?,?)", "{\"token\":\"trace secret\"}", "trace summary", "{\"trace\":\"machine secret\"}");
        jdbc.update("UPDATE app_state SET assistant_completed_hook_script=? WHERE id=1", "hook secret");
        jdbc.update("INSERT INTO mcp_servers (id,name,url,headers_json) VALUES (1,?,?,?)", "mcp secret", "https://mcp", "{\"Authorization\":\"oauth secret\"}");
    }

    private record TestDatabase(DataSource dataSource, JdbcTemplate jdbc) { }
}
