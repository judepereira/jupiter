package com.judepereira.jupiter.agent.llm.dto;

import lombok.Getter;

@Getter
public class ModelResponse {
    private final String assistantText;
    private final ToolCall toolCall; // nullable
    private final ModelResponseMetadata metadata;

    public ModelResponse(String assistantText, ToolCall toolCall, ModelResponseMetadata metadata) {
        this.assistantText = assistantText;
        this.toolCall = toolCall;
        this.metadata = metadata == null ? ModelResponseMetadata.empty() : metadata;
    }
}
