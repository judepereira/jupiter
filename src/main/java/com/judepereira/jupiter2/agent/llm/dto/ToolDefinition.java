package com.judepereira.jupiter2.agent.llm.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@Getter
@RequiredArgsConstructor
public class ToolDefinition {
    private final String name;
    private final String description;
    private final Map<String, Object> schema;
}
