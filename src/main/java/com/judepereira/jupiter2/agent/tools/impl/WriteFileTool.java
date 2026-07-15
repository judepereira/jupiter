package com.judepereira.jupiter2.agent.tools.impl;

import com.judepereira.jupiter2.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter2.agent.tools.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class WriteFileTool implements AgentTool {
    private static final ToolDefinition DEF = new ToolDefinition(
            "write_file",
            "Write full content to a file (overwrites)",
            Map.of(
                    "path", Map.of("type", "string", "description", "relative file path to write"),
                    "content", Map.of("type", "string", "description", "full content to write")
            )
    );

    @Override
    public String name() { return "write_file"; }

    @Override
    public ToolDefinition definition() { return DEF; }

    @Override
    public ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext context) throws Exception {
        if (!context.isAllowWrite()) {
            return new ToolExecutionResult(false, "writing is disabled by configuration", Map.of());
        }
        String rel = (String) args.get("path");
        String content = (String) args.get("content");
        if (rel == null || content == null) return new ToolExecutionResult(false, "missing path or content", Map.of());
        Path p = FileUtils.resolveWorkspacePath(context.getWorkspaceRoot(), rel);
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new ToolExecutionResult(true, "wrote file: " + rel, Map.of("path", rel));
    }
}
