package com.judepereira.jupiter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.judepereira.jupiter.testsupport.SQLiteTestSupport;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AppStateSchemaMigrationTests {
    @Test
    void v31RemovesAnthropicOAuthColumnsButKeepsOpenAiOAuthColumns() throws Exception {
        var dataSource = SQLiteTestSupport
                .fileBackedDataSource(Files.createTempDirectory("jupiter-schema-").resolve("app-state.db"));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        Set<String> columns = new HashSet<>(new JdbcTemplate(dataSource).query("PRAGMA table_info(app_state)",
                (rs, rowNum) -> rs.getString("name")));
        assertThat(columns).doesNotContain("anthropic_access_token", "anthropic_refresh_token", "anthropic_expires_at",
                "anthropic_scopes", "anthropic_account_json").contains("openai_access_token", "openai_refresh_token",
                        "openai_expires_at", "openai_id_token", "openai_account_id");
    }
}
