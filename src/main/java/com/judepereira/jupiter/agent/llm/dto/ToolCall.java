package com.judepereira.jupiter.agent.llm.dto;

import lombok.Getter;

import java.util.Map;

@Getter
public class ToolCall {
    private final String toolCallId;
    private final String toolName;
    private final Map<String, Object> arguments;

    public ToolCall(String toolName, Map<String, Object> arguments) {
        this(null, toolName, arguments);
    }

    public ToolCall(String toolCallId, String toolName, Map<String, Object> arguments) {
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.arguments = arguments;
    }
}
