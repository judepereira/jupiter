package com.judepereira.jupiter2.agent.llm.dto;

public class ModelResponse {
    private final String assistantText;
    private final ToolCall toolCall; // nullable

    public ModelResponse(String assistantText, ToolCall toolCall) {
        this.assistantText = assistantText;
        this.toolCall = toolCall;
    }

    public String getAssistantText() {
        return assistantText;
    }

    public ToolCall getToolCall() {
        return toolCall;
    }
}
