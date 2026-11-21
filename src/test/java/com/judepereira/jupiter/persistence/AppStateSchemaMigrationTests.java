package com.judepereira.jupiter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.mcp.InboundMcpTokenService;
import com.judepereira.jupiter.security.TextEncryptor;
import com.judepereira.jupiter.testsupport.SQLiteTestSupport;
import com.judepereira.jupiter.testsupport.TestEncryptionSupport;
import java.nio.file.Files;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class AppStateSchemaMigrationTests {
    @Test
    void v31RemovesAnthropicOAuthColumnsButKeepsOpenAiOAuthColumns() throws Exception {
        var dataSource = SQLiteTestSupport
                .fileBackedDataSource(Files.createTempDirectory("jupiter-schema-").resolve("app-state.db"));
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        flyway.migrate();

        Set<String> columns = new HashSet<>(new JdbcTemplate(dataSource).query("PRAGMA table_info(app_state)",
                (rs, rowNum) -> rs.getString("name")));
        assertThat(columns).doesNotContain("anthropic_access_token", "anthropic_refresh_token", "anthropic_expires_at",
                "anthropic_scopes", "anthropic_account_json").contains("openai_access_token", "openai_refresh_token",
                        "openai_expires_at", "openai_id_token", "openai_account_id");
    }

    @Test
    void inboundMcpCredentialsUpgradeFromV31WithEncryptedFieldsAndHashOnlyTokens() throws Exception {
        var dataSource = SQLiteTestSupport
                .fileBackedDataSource(Files.createTempDirectory("jupiter-schema-").resolve("app-state.db"));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("31").load().migrate();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Set<String> tables = new HashSet<>(jdbc.query("SELECT name FROM sqlite_master WHERE type = 'table'",
                (rs, rowNum) -> rs.getString("name")));
        assertThat(tables).doesNotContain("app_settings").contains("inbound_mcp_credentials");
        Set<String> columns = new HashSet<>(
                jdbc.query("PRAGMA table_info(inbound_mcp_credentials)", (rs, rowNum) -> rs.getString("name")));
        assertThat(columns).contains("id", "name", "token_hash", "all_projects", "project_ids_json",
                "allowed_tools_json", "created_at").doesNotContain("token_ciphertext", "rotated_at", "revoked_at");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pragma_index_list('inbound_mcp_credentials') il "
                        + "JOIN pragma_index_info(il.name) ii WHERE il.\"unique\" = 1 AND ii.name = 'token_hash'",
                Integer.class)).isEqualTo(1);

        TextEncryptor crypto = TestEncryptionSupport.encryptor();
        String token = "jup_test-migration-token";
        var repository = new AppStateRepository(new NamedParameterJdbcTemplate(dataSource), crypto, new ObjectMapper());
        var service = new InboundMcpTokenService(repository, new ObjectMapper());
        repository.insertInboundMcpCredential("migration-credential", "Migration", token, true, "[]",
                "[\"list_projects\"]", Instant.parse("2025-01-01T00:00:00Z"));

        assertThat(jdbc.queryForObject("SELECT token_hash FROM inbound_mcp_credentials WHERE id = ?", String.class,
                "migration-credential")).isEqualTo(crypto.blindIndex(token, "inbound-mcp-token"));
        assertThat(jdbc.queryForObject("SELECT name FROM inbound_mcp_credentials WHERE id = ?", String.class,
                "migration-credential")).isNotEqualTo("Migration");
        assertThat(jdbc.queryForObject("SELECT project_ids_json FROM inbound_mcp_credentials WHERE id = ?",
                String.class, "migration-credential")).isNotEqualTo("[]");
        assertThat(service.authenticate(token)).isNotNull();
        assertThat(service.authenticate(token).tools()).containsExactly("list_projects");
        jdbc.update("UPDATE inbound_mcp_credentials SET name = 'corrupt ciphertext' WHERE id = ?",
                "migration-credential");
        assertThat(service.authenticate(token)).isNotNull();
        Assertions.assertThatThrownBy(service::list).isInstanceOf(RuntimeException.class);
        service.edit("migration-credential", true, Set.of(), Set.of("list_projects"));
        assertThatThrownBy(service::list).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.edit("missing", true, Set.of(), Set.of("list_projects")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.revoke("missing")).isInstanceOf(IllegalArgumentException.class);
        service.revoke("migration-credential");
        assertThat(service.authenticate(token)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbound_mcp_credentials WHERE id = ?", Integer.class,
                "migration-credential")).isZero();
        assertThat(service.list()).isEmpty();
    }

    @Test
    void upgradeFromMainV31HistoryCreatesAgentPreferencesAtV32() throws Exception {
        var dataSource = SQLiteTestSupport
                .fileBackedDataSource(Files.createTempDirectory("jupiter-schema-").resolve("app-state.db"));
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("31")
                .load();
        flyway.migrate();
        assertThat(new JdbcTemplate(dataSource)
                .queryForObject("SELECT version FROM flyway_schema_history WHERE version = '31'", String.class))
                .isEqualTo("31");

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'agent_model_preferences'",
                String.class)).isEqualTo("agent_model_preferences");
    }
}
