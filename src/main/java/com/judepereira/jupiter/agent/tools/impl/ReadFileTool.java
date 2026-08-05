package com.judepereira.jupiter.agent.tools.impl;

import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.llm.dto.ToolSchema;
import com.judepereira.jupiter.agent.tools.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static com.judepereira.jupiter.agent.llm.dto.ToolParameter.integer;
import static com.judepereira.jupiter.agent.llm.dto.ToolParameter.string;

public class ReadFileTool implements AgentTool {
    private static final ToolDefinition DEF = new ToolDefinition(
            "read_file",
            "Read a file from the workspace (utf-8) with optional line range",
            ToolSchema.object(
                    string("path", "relative file path to read"),
                    integer("startLine", "optional 1-based start line"),
                    integer("endLine", "optional 1-based end line")
            ).required("path")
    );
    private final int MAX_CHARS = 50_000;

    @Override
    public String name() { return "read_file"; }

    @Override
    public ToolDefinition definition() { return DEF; }

    @Override
    public ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext context) throws Exception {
        String rel = (String) args.get("path");
        if (rel == null) {
            return new ToolExecutionResult(false, "missing path", Map.of());
        }
        Path p = FileUtils.resolveWorkspacePath(context.getWorkspaceRoot(), rel);
        if (!Files.exists(p) || !Files.isRegularFile(p)) {
            return new ToolExecutionResult(false, "file not found: " + rel, Map.of());
        }
        String all = FileUtils.readUtf8(p, MAX_CHARS);
        Integer start = args.get("startLine") instanceof Number ? ((Number) args.get("startLine")).intValue() : null;
        Integer end = args.get("endLine") instanceof Number ? ((Number) args.get("endLine")).intValue() : null;
        String text = all;
        if (start != null || end != null) {
            String[] lines = all.split("\n", -1);
            int s = start == null ? 1 : Math.max(1, start);
            int e = end == null ? lines.length : Math.min(lines.length, end);
            if (s > e) {
                return new ToolExecutionResult(false, "invalid line range", Map.of());
            }
            StringBuilder sb = new StringBuilder();
            for (int i = s - 1; i < e; i++) {
                sb.append(lines[i]);
                if (i < e - 1) sb.append('\n');
            }
            text = sb.toString();
        }
        return new ToolExecutionResult(true, text, Map.of("path", rel));
    }
}
