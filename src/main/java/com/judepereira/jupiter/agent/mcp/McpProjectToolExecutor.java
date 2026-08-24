package com.judepereira.jupiter.agent.mcp;

import com.judepereira.jupiter.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter.agent.tools.ToolExecutionResult;

import java.util.Map;

public interface McpProjectToolExecutor {
    String modelToolName();

    String serverSlug();

    String toolSlug();

    ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext context) throws Exception;
}
