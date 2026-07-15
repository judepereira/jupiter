package com.judepereira.jupiter2.agent.llm.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@Getter
@RequiredArgsConstructor
public class ToolCall {
    private final String toolName;
    private final Map<String, Object> arguments;
}
