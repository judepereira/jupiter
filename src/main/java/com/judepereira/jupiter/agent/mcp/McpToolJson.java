package com.judepereira.jupiter.agent.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

final class McpToolJson {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private McpToolJson() {
    }

    static String toJson(Map<String, Object> args) {
        return toJsonValue(args == null ? Map.of() : args, "arguments");
    }

    static String toJsonValue(Object value, String description) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize MCP tool " + description, e);
        }
    }
}
