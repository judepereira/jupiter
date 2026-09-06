package com.judepereira.jupiter.security;

import java.util.HashMap;
import java.util.Map;

/** Removes the encryption key before starting child processes so it cannot be inherited by them. */
public final class ProcessEnvironmentSanitizer {
    public static final String ENCRYPTION_KEY = "JUPITER_ENCRYPTION_KEY";

    private ProcessEnvironmentSanitizer() {
    }

    public static Map<String, String> sanitize(Map<String, String> environment) {
        Map<String, String> sanitized = new HashMap<>(environment);
        sanitized.remove(ENCRYPTION_KEY);
        return sanitized;
    }

    public static ProcessBuilder sanitize(ProcessBuilder builder) {
        builder.environment().remove(ENCRYPTION_KEY);
        return builder;
    }
}
