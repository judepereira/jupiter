package com.judepereira.jupiter.agent.tools.impl;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RunCommandEnvironmentTest {
    @Test
    void emptyAllowlistExposesNoHostVariables() {
        Map<String, String> environment = RunCommandTool.buildCommandEnvironment(
                Map.of("HOST_ONLY", "host-value"), Set.of(), Map.of());

        assertThat(environment).doesNotContainKey("HOST_ONLY");
    }

    @Test
    void allowlistPassesOnlyRequestedHostVariables() {
        Map<String, String> environment = RunCommandTool.buildCommandEnvironment(
                Map.of("ALLOWED_HOST", "allowed", "OTHER_HOST", "hidden"),
                Set.of("ALLOWED_HOST"), Map.of());

        assertThat(environment).containsEntry("ALLOWED_HOST", "allowed")
                .doesNotContainKey("OTHER_HOST");
    }

    @Test
    void projectVariablesOverrideAllowedHostValues() {
        Map<String, String> environment = RunCommandTool.buildCommandEnvironment(
                Map.of("SHARED", "host-value"), Set.of("SHARED"),
                Map.of("SHARED", "project-value", "PROJECT_ONLY", "project"));

        assertThat(environment).containsEntry("SHARED", "project-value")
                .containsEntry("PROJECT_ONLY", "project");
    }

    @Test
    void authCredentialsAreBlockedFromBothSources() {
        Map<String, String> environment = RunCommandTool.buildCommandEnvironment(
                Map.of("JUPITER_HTTP_AUTH_PASSWORD", "host-password",
                        "JUPITER_HTTP_AUTH_USERNAME", "host-user"),
                Set.of("JUPITER_HTTP_AUTH_PASSWORD", "JUPITER_HTTP_AUTH_USERNAME"),
                Map.of("JUPITER_HTTP_AUTH_PASSWORD", "project-password",
                        "JUPITER_HTTP_AUTH_USERNAME", "project-user"));

        assertThat(environment).doesNotContainKeys("JUPITER_HTTP_AUTH_PASSWORD", "JUPITER_HTTP_AUTH_USERNAME");
    }
}
