package com.judepereira.jupiter.agent.tools;

import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import java.util.Map;

public interface AgentTool {
    String name();

    ToolDefinition definition();

    ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext context) throws Exception;
}
