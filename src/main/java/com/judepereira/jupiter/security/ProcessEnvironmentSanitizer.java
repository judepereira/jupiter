package com.judepereira.jupiter.security;

import java.util.Map;
import java.util.Set;

/**
 * Removes application and Jupiter credentials before starting child processes.
 */
public final class ProcessEnvironmentSanitizer {
    public static final String ENCRYPTION_KEY = "JUPITER_ENCRYPTION_KEY";
    public static final String HTTP_AUTH_PASSWORD = "JUPITER_HTTP_AUTH_PASSWORD";
    public static final String HTTP_AUTH_USERNAME = "JUPITER_HTTP_AUTH_USERNAME";
    public static final String ANTHROPIC_API_KEY = "ANTHROPIC_API_KEY";
    public static final String OPENAI_API_KEY = "OPENAI_API_KEY";

    private static final Set<String> SENSITIVE_VARIABLES = Set.of(ENCRYPTION_KEY, HTTP_AUTH_PASSWORD,
            HTTP_AUTH_USERNAME, ANTHROPIC_API_KEY, OPENAI_API_KEY);
    private static final Set<String> TRUSTED_TERMINAL_SENSITIVE_VARIABLES = Set.of(ENCRYPTION_KEY, ANTHROPIC_API_KEY,
            OPENAI_API_KEY);

    private ProcessEnvironmentSanitizer() {
    }

    /** Sanitizes environments for untrusted child processes. */
    public static void sanitize(Map<String, String> environment) {
        environment.keySet().removeAll(SENSITIVE_VARIABLES);
    }

    /**
     * Sanitizes trusted terminal environments without exposing encryption or
     * provider credentials.
     */
    public static void sanitizeTrustedTerminal(Map<String, String> environment) {
        environment.keySet().removeAll(TRUSTED_TERMINAL_SENSITIVE_VARIABLES);
    }

    public static void sanitize(ProcessBuilder builder) {
        builder.environment().keySet().removeAll(SENSITIVE_VARIABLES);
    }
}
