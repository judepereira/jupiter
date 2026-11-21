package com.judepereira.jupiter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.mcp.InboundMcpTokenService;
import com.judepereira.jupiter.security.TextEncryptor;
import com.judepereira.jupiter.testsupport.SQLiteTestSupport;
import com.judepereira.jupiter.testsupport.TestEncryptionSupport;
import java.nio.file.Files;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
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
    void inboundMcpCredentialsUseUniqueTokenHashIndexInV34() throws Exception {
        var dataSource = SQLiteTestSupport
                .fileBackedDataSource(Files.createTempDirectory("jupiter-schema-").resolve("app-state.db"));
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        flyway.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE version = '34'", String.class))
                .isEqualTo("34");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pragma_index_list('inbound_mcp_credentials') il "
                        + "JOIN pragma_index_info(il.name) ii WHERE il.\"unique\" = 1 AND ii.name = 'token_hash'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' "
                + "AND name = 'idx_inbound_mcp_credentials_token_hash'", Integer.class)).isZero();
    }

    @Test
    void v35DropsRecoverableInboundTokenAndInitializationMarker() throws Exception {
        var dataSource = SQLiteTestSupport
                .fileBackedDataSource(Files.createTempDirectory("jupiter-schema-").resolve("app-state.db"));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("34").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Set<String> before = new HashSet<>(
                jdbc.query("PRAGMA table_info(inbound_mcp_credentials)", (rs, rowNum) -> rs.getString("name")));
        assertThat(before).contains("token_ciphertext", "token_hash", "name");
        TextEncryptor crypto = TestEncryptionSupport.encryptor();
        String testToken = "jup_test-migration-token";
        String id = "migration-credential";
        jdbc.update(
                "INSERT INTO inbound_mcp_credentials(id, name, token_hash, token_ciphertext, all_projects, "
                        + "project_ids_json, allowed_tools_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, crypto.encrypt("Default", "inbound_mcp_credentials.name"),
                crypto.blindIndex(testToken, "inbound-mcp-token"),
                crypto.encrypt(testToken, "inbound_mcp_credentials.token_ciphertext"), 1,
                crypto.encrypt("[]", "inbound_mcp_credentials.project_ids_json"),
                crypto.encrypt("[\"list_projects\"]", "inbound_mcp_credentials.allowed_tools_json"),
                Instant.parse("2025-01-01T00:00:00Z").toString());
        jdbc.update("INSERT INTO app_settings(setting_key, setting_value) VALUES ('inbound_mcp_initialized', 'true')");

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        var repository = new AppStateRepository(new NamedParameterJdbcTemplate(dataSource), crypto, new ObjectMapper());
        var service = new InboundMcpTokenService(repository, new ObjectMapper());
        var authenticated = service.authenticate(testToken);
        assertThat(authenticated).isNotNull();
        assertThat(authenticated.name()).isEqualTo("Default");
        assertThat(authenticated.tools()).containsExactly("list_projects");
        assertThat(authenticated.allProjects()).isTrue();
        assertThat(service.list()).hasSize(1);
        service.edit(id, true, Set.of(), Set.of("list_agents"));
        assertThat(service.authenticate(testToken).tools()).containsExactly("list_agents");
        service.revoke(id);
        assertThat(service.authenticate(testToken)).isNull();
        assertThat(service.list()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT name FROM inbound_mcp_credentials WHERE id = ?", String.class, id))
                .isNotEqualTo("Default");
        assertThat(jdbc.queryForObject("SELECT allowed_tools_json FROM inbound_mcp_credentials WHERE id = ?",
                String.class, id)).isNotEqualTo("[\"list_projects\"]");

        Set<String> after = new HashSet<>(
                jdbc.query("PRAGMA table_info(inbound_mcp_credentials)", (rs, rowNum) -> rs.getString("name")));
        assertThat(after).doesNotContain("token_ciphertext", "rotated_at").contains("token_hash", "name");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_settings WHERE setting_key = 'inbound_mcp_initialized'", Integer.class))
                .isZero();
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

        flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        flyway.migrate();

        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'agent_model_preferences'",
                String.class)).isEqualTo("agent_model_preferences");
    }
}
