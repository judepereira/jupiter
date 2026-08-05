package com.judepereira.jupiter.agent.harness;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@Getter
@RequiredArgsConstructor
public class ToolCallTrace {
    private final String toolCallId;
    private final String toolName;
    private final Map<String, Object> args;
    private final boolean success;
    private final String textSummary;
    private final Map<String, Object> machineSummary;
}
