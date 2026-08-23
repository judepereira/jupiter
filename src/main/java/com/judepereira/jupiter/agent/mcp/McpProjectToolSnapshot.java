package com.judepereira.jupiter.agent.mcp;

import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;

import java.util.List;
import java.util.Map;

public record McpProjectToolSnapshot(long projectId, List<ToolDefinition> toolDefinitions, Map<String, McpProjectToolExecutor> executors) {
    public McpProjectToolSnapshot {
        toolDefinitions = List.copyOf(toolDefinitions);
        executors = Map.copyOf(executors);
    }
}
