package com.judepereira.jupiter.security;

import java.util.Map;
import java.util.Set;

/** Removes sensitive Jupiter credentials before starting child processes. */
public final class ProcessEnvironmentSanitizer {
    public static final String ENCRYPTION_KEY = "JUPITER_ENCRYPTION_KEY";
    public static final String HTTP_AUTH_PASSWORD = "JUPITER_HTTP_AUTH_PASSWORD";
    public static final String HTTP_AUTH_USERNAME = "JUPITER_HTTP_AUTH_USERNAME";

    private static final Set<String> SENSITIVE_VARIABLES = Set.of(
            ENCRYPTION_KEY, HTTP_AUTH_PASSWORD, HTTP_AUTH_USERNAME);

    private ProcessEnvironmentSanitizer() {
    }

    public static void sanitize(Map<String, String> environment) {
        environment.keySet().removeAll(SENSITIVE_VARIABLES);
    }

    public static void sanitize(ProcessBuilder builder) {
        builder.environment().keySet().removeAll(SENSITIVE_VARIABLES);
    }
}
