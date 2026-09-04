package com.judepereira.jupiter.agent.tools.impl;

import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.llm.dto.ToolSchema;
import com.judepereira.jupiter.agent.tools.*;

import java.util.Map;

import static com.judepereira.jupiter.agent.llm.dto.ToolParameter.string;

public class ListFilesTool implements AgentTool {
    private static final ToolDefinition DEF = new ToolDefinition(
            "list_files",
            "List files under a relative path",
            ToolSchema.object(
                    string("path", "relative path to list"),
                    string("include", "optional glob filter, e.g. **/*.java")
            ).required("path")
    );
    private final RipgrepToolSupport ripgrep;

    public ListFilesTool(RipgrepToolSupport ripgrep) {
        this.ripgrep = ripgrep;
    }

    @Override
    public String name() {
        return "list_files";
    }

    @Override
    public ToolDefinition definition() { return DEF; }

    @Override
    public ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext context) {
        String rel = (String) args.getOrDefault("path", "");
        String include = (String) args.getOrDefault("include", "");
        return ripgrep.listFiles(context.getWorkspaceRoot(), rel, include, context.getCommandTimeoutSeconds());
    }
}
