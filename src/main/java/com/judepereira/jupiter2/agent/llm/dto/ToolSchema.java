package com.judepereira.jupiter2.agent.llm.dto;

import java.util.List;

public record ToolSchema(String description, List<ToolParameter> properties, List<String> required,
                         Boolean additionalProperties) {

    public ToolSchema {
        properties = List.copyOf(properties);
        required = List.copyOf(required);
    }

    public static ToolSchema object(ToolParameter... properties) {
        return new ToolSchema(null, List.of(properties), List.of(), null);
    }

    public static ToolSchema object(String description, ToolParameter... properties) {
        return new ToolSchema(description, List.of(properties), List.of(), null);
    }

    public ToolSchema description(String description) {
        return new ToolSchema(description, properties, required, additionalProperties);
    }

    public ToolSchema required(String... names) {
        return new ToolSchema(description, properties, List.of(names), additionalProperties);
    }

    public ToolSchema additionalProperties(boolean additionalProperties) {
        return new ToolSchema(description, properties, required, additionalProperties);
    }
}
