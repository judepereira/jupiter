package com.judepereira.jupiter.security;

import java.util.Map;

/**
 * The variables supplied by the bootstrap envelope, excluding the encryption
 * key.
 */
public record RuntimeEnvironment(Map<String, String> values) {
    public RuntimeEnvironment {
        if (values.containsKey(ProcessEnvironmentSanitizer.ENCRYPTION_KEY)) {
            throw new IllegalArgumentException("encryption key must not be part of runtime environment");
        }
        values = Map.copyOf(values);
    }

    public String get(String name) {
        return values.get(name);
    }

    public Map<String, String> asMap() {
        return values;
    }
}
