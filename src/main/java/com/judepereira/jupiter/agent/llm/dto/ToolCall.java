package com.judepereira.jupiter.agent.llm.dto;

import java.util.Map;
import lombok.Getter;

@Getter
public class ToolCall {
    private final String toolCallId;
    private final String toolName;
    private final Map<String, Object> arguments;

    public ToolCall(String toolCallId, String toolName, Map<String, Object> arguments) {
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.arguments = arguments;
    }
}
