package com.judepereira.jupiter.agent.tools.impl;

import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.llm.dto.ToolSchema;
import com.judepereira.jupiter.agent.tools.*;

import java.util.Map;

import static com.judepereira.jupiter.agent.llm.dto.ToolParameter.string;

public class SearchCodeTool implements AgentTool {
    private static final ToolDefinition DEF = ToolDefinition.builtIn(
            "search_code",
            "Search regex across files in workspace",
            ToolSchema.object(
                    string("path", "relative path root to search"),
                    string("pattern", "regex pattern to search (required)"),
                    string("include", "optional glob include e.g. **/*.java")
            ).required("pattern")
    );
    private final RipgrepToolSupport ripgrep;

    public SearchCodeTool(RipgrepToolSupport ripgrep) {
        this.ripgrep = ripgrep;
    }

    @Override
    public String name() { return "search_code"; }

    @Override
    public ToolDefinition definition() { return DEF; }

    @Override
    public ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext context) {
        String rel = (String) args.getOrDefault("path", "");
        String pattern = (String) args.get("pattern");
        String include = (String) args.getOrDefault("include", "");
        return ripgrep.searchCode(context.getWorkspaceRoot(), rel, pattern, include, context.getCommandTimeoutSeconds());
    }
}
