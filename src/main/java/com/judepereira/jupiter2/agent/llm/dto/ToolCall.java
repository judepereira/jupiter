package com.judepereira.jupiter2.agent.llm.dto;

import java.util.Map;

public class ToolCall {
    private final String toolName;
    private final Map<String, Object> arguments;

    public ToolCall(String toolName, Map<String, Object> arguments) {
        this.toolName = toolName;
        this.arguments = arguments;
    }

    public String getToolName() {
        return toolName;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }
}
