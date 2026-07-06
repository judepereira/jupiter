package com.judepereira.jupiter2.agent.tools.impl;

import com.judepereira.jupiter2.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter2.agent.tools.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Simple patch tool: replace first occurrence of oldText with newText in file.
 */
public class ApplyPatchTool implements AgentTool {
    private final ToolDefinition def;

    public ApplyPatchTool() {
        Map<String, Object> schema = Map.of(
                "path", Map.of("type", "string", "description", "relative file path to patch"),
                "oldText", Map.of("type", "string", "description", "text to replace (required)"),
                "newText", Map.of("type", "string", "description", "replacement text (required)")
        );
        this.def = new ToolDefinition(name(), "Apply a simple text replace patch to a file", schema);
    }

    @Override
    public String name() { return "apply_patch"; }

    @Override
    public ToolDefinition definition() { return def; }

    @Override
    public ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext context) throws Exception {
        if (!context.isAllowWrite()) return new ToolExecutionResult(false, "writing is disabled by configuration", Map.of());
        String rel = (String) args.get("path");
        String oldText = (String) args.get("oldText");
        String newText = (String) args.get("newText");
        if (rel == null || oldText == null || newText == null) return new ToolExecutionResult(false, "missing args", Map.of());
        Path p = FileUtils.resolveWorkspacePath(context.getWorkspaceRoot(), rel);
        if (!Files.exists(p)) return new ToolExecutionResult(false, "file not found: " + rel, Map.of());
        String content = Files.readString(p);
        int idx = content.indexOf(oldText);
        if (idx < 0) return new ToolExecutionResult(false, "oldText not found", Map.of());
        String updated = content.substring(0, idx) + newText + content.substring(idx + oldText.length());
        Files.writeString(p, updated);
        return new ToolExecutionResult(true, "applied patch to: " + rel, Map.of("path", rel));
    }
}
