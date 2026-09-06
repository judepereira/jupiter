package com.judepereira.jupiter.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalManagerEnvironmentTests {

    @Test
    void removesHttpAuthCredentialsButPreservesProjectEnvironment() {
        Map<String, String> environment = TerminalManager.terminalEnvironment(Map.of(
                "JUPITER_HTTP_AUTH_PASSWORD", "secret-password",
                "JUPITER_HTTP_AUTH_USERNAME", "secret-user",
                "JUPITER_ENCRYPTION_KEY", "secret-key",
                "PROJECT_ENV_VAR", "project-value"));

        assertThat(environment)
                .doesNotContainKeys("JUPITER_HTTP_AUTH_PASSWORD", "JUPITER_HTTP_AUTH_USERNAME", "JUPITER_ENCRYPTION_KEY")
                .containsEntry("PROJECT_ENV_VAR", "project-value");
    }
}
