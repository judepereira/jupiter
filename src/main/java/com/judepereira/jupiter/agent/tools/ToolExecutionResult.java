package com.judepereira.jupiter.agent.tools;

import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ToolExecutionResult {
    private final boolean success;
    private final String text;
    private final Map<String, Object> machine;
}
