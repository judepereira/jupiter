package com.judepereira.jupiter2.agent.tools.impl;

import com.judepereira.jupiter2.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter2.agent.llm.dto.ToolSchema;
import com.judepereira.jupiter2.agent.tools.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static com.judepereira.jupiter2.agent.llm.dto.ToolParameter.string;

/**
 * Simple patch tool: replace first occurrence of oldText with newText in file.
 */
public class ApplyPatchTool implements AgentTool {
    private static final ToolDefinition DEF = new ToolDefinition(
            "apply_patch",
            "Apply a simple text replace patch to a file",
            ToolSchema.object(
                    string("path", "relative file path to patch"),
                    string("oldText", "text to replace (required)"),
                    string("newText", "replacement text (required)")
            ).required("path", "oldText", "newText")
    );

    @Override
    public String name() { return "apply_patch"; }

    @Override
    public ToolDefinition definition() { return DEF; }

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
