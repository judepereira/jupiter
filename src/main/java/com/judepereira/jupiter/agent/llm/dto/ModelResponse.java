package com.judepereira.jupiter.agent.llm.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ModelResponse {
    private final String assistantText;
    private final ToolCall toolCall; // nullable
}
