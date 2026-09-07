package com.judepereira.jupiter.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TerminalManagerEnvironmentTests {

    @Test
    void terminalChildDoesNotReceiveEncryptionKey() throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux"));
        Map<String, String> environment = new java.util.HashMap<>(Map.of(
                "JUPITER_ENCRYPTION_KEY", "secret", "TERMINAL_SENTINEL", "preserved"));
        var process = TerminalManager.startProcess("/tmp", new String[]{"/usr/bin/env"}, environment);
        try {
            assertThat(process.waitFor(2, TimeUnit.SECONDS)).isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(output).doesNotContain("JUPITER_ENCRYPTION_KEY=")
                    .contains("TERMINAL_SENTINEL=preserved");
        } finally {
            process.destroyForcibly();
        }
    }

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
