package com.judepereira.jupiter.agent.catalog;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

public enum ThinkingLevel {
    LOW,
    MEDIUM,
    HIGH;

    @JsonCreator
    public static ThinkingLevel fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("thinking level is required");
        }
        return ThinkingLevel.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
