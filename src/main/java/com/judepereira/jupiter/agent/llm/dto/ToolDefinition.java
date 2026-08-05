package com.judepereira.jupiter.agent.llm.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ToolDefinition {
    private final String name;
    private final String description;
    private final ToolSchema schema;
}
