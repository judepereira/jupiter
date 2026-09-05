package com.judepereira.jupiter.agent.llm.dto;

import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.Getter;

@Getter
public class ToolDefinition {
    private final String name;
    private final String description;
    private final ToolSchema schema;
    private final ToolSpecification nativeToolSpecification;

    private ToolDefinition(String name, String description, ToolSchema schema, ToolSpecification nativeToolSpecification) {
        this.name = name;
        this.description = description;
        this.schema = schema;
        this.nativeToolSpecification = nativeToolSpecification;
    }

    public static ToolDefinition builtIn(String name, String description, ToolSchema schema) {
        return new ToolDefinition(name, description, schema, null);
    }

    public static ToolDefinition withNativeToolSpecification(String name, String description, ToolSchema schema,
                                                             ToolSpecification nativeToolSpecification) {
        return new ToolDefinition(name, description, schema, nativeToolSpecification);
    }
}
