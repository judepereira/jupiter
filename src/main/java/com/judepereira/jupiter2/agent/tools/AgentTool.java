package com.judepereira.jupiter2.agent.tools;

import com.judepereira.jupiter2.agent.llm.dto.ToolDefinition;
import java.util.Map;

public interface AgentTool {
    String name();

    ToolDefinition definition();

    ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext context) throws Exception;
}
