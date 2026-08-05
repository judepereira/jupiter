package com.judepereira.jupiter.agent.tools;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@Getter
@RequiredArgsConstructor
public class ToolExecutionResult {
    private final boolean success;
    private final String text;
    private final Map<String, Object> machine;
}
