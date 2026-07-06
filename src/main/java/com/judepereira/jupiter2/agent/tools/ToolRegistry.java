package com.judepereira.jupiter2.agent.tools;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ToolRegistry {
    private final Map<String, AgentTool> tools = new HashMap<>();

    public void register(AgentTool tool) {
        tools.put(tool.name(), tool);
    }

    public Map<String, AgentTool> all() {
        return Collections.unmodifiableMap(tools);
    }

    public AgentTool get(String name) {
        return tools.get(name);
    }

    public ToolExecutionResult executeByName(String name, Map<String, Object> args, ToolExecutionContext context) throws Exception {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }
        return tool.execute(args, context);
    }
}
