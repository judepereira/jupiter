package com.judepereira.jupiter2.agent.llm.openai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public final class ToolArgumentsCodec {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public String serialize(Map<String, Object> arguments) {
        try {
            return OBJECT_MAPPER.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize tool arguments", e);
        }
    }

    public Map<String, Object> parse(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(text, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse tool call arguments", e);
        }
    }
}
