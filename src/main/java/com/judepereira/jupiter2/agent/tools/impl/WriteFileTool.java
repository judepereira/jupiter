package com.judepereira.jupiter2.agent.tools.impl;

import com.judepereira.jupiter2.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter2.agent.tools.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class WriteFileTool implements AgentTool {
    private final ToolDefinition def;

    public WriteFileTool() {
        Map<String, Object> schema = Map.of(
                "path", Map.of("type", "string", "description", "relative file path to write"),
                "content", Map.of("type", "string", "description", "full content to write")
        );
        this.def = new ToolDefinition(name(), "Write full content to a file (overwrites)", schema);
    }

    @Override
    public String name() { return "write_file"; }

    @Override
    public ToolDefinition definition() { return def; }

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
