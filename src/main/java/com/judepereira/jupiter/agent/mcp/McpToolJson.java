package com.judepereira.jupiter.agent.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

final class McpToolJson {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private McpToolJson() {
    }

    static String toJson(Map<String, Object> args) {
        try {
            return OBJECT_MAPPER.writeValueAsString(args == null ? Map.of() : args);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize MCP tool arguments", e);
        }
    }
}
