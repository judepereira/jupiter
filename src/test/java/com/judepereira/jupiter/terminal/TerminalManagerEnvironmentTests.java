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
                "PROJECT_ENV_VAR", "project-value"));

        assertThat(environment)
                .doesNotContainKeys("JUPITER_HTTP_AUTH_PASSWORD", "JUPITER_HTTP_AUTH_USERNAME")
                .containsEntry("PROJECT_ENV_VAR", "project-value");
    }
}
