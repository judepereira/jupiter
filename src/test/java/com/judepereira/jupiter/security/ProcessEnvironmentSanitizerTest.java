package com.judepereira.jupiter.security;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessEnvironmentSanitizerTest {
    @Test
    void sanitizeMapRemovesSensitiveCredentialsInPlaceAndPreservesUnrelatedVariables() {
        Map<String, String> environment = new HashMap<>(Map.of(
                "JUPITER_ENCRYPTION_KEY", "key",
                "JUPITER_HTTP_AUTH_PASSWORD", "password",
                "JUPITER_HTTP_AUTH_USERNAME", "username",
                "PROJECT_ENV_VAR", "project"));

        ProcessEnvironmentSanitizer.sanitize(environment);

        assertThat(environment)
                .doesNotContainKeys("JUPITER_ENCRYPTION_KEY", "JUPITER_HTTP_AUTH_PASSWORD", "JUPITER_HTTP_AUTH_USERNAME")
                .containsEntry("PROJECT_ENV_VAR", "project");
    }

    @Test
    void sanitizedBuilderDoesNotLeakCredentialsToRealChild() throws Exception {
        ProcessBuilder builder = new ProcessBuilder("/usr/bin/env");
        builder.environment().put("JUPITER_ENCRYPTION_KEY", "key");
        builder.environment().put("SECURITY_SENTINEL", "preserved");
        ProcessEnvironmentSanitizer.sanitize(builder);

        Process process = builder.start();
        try {
            assertThat(process.waitFor(2, TimeUnit.SECONDS)).isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(output).doesNotContain("JUPITER_ENCRYPTION_KEY=").contains("SECURITY_SENTINEL=preserved");
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void sanitizeProcessBuilderRemovesSensitiveCredentialsAndPreservesUnrelatedVariables() {
        ProcessBuilder builder = new ProcessBuilder();
        builder.environment().putAll(Map.of(
                "JUPITER_ENCRYPTION_KEY", "key",
                "JUPITER_HTTP_AUTH_PASSWORD", "password",
                "JUPITER_HTTP_AUTH_USERNAME", "username",
                "PROJECT_ENV_VAR", "project"));

        ProcessEnvironmentSanitizer.sanitize(builder);

        assertThat(builder.environment())
                .doesNotContainKeys("JUPITER_ENCRYPTION_KEY", "JUPITER_HTTP_AUTH_PASSWORD", "JUPITER_HTTP_AUTH_USERNAME")
                .containsEntry("PROJECT_ENV_VAR", "project");
    }
}
