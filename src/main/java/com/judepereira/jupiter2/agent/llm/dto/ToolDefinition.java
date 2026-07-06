package com.judepereira.jupiter2.agent.llm.dto;

import java.util.Map;

public class ToolDefinition {
    private final String name;
    private final String description;
    private final Map<String, Object> schema;

    public ToolDefinition(String name, String description, Map<String, Object> schema) {
        this.name = name;
        this.description = description;
        this.schema = schema;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, Object> getSchema() {
        return schema;
    }
}
