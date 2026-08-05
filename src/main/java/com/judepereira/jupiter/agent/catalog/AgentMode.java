package com.judepereira.jupiter.agent.catalog;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum AgentMode {
    AGENT,
    SUBAGENT;

    @JsonCreator
    public static AgentMode fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("mode is required");
        }
        return switch (value.trim()) {
            case "agent" -> AGENT;
            case "subagent" -> SUBAGENT;
            default -> throw new IllegalArgumentException("Invalid mode: " + value);
        };
    }
}
