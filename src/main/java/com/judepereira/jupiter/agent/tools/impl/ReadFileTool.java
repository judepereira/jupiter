package com.judepereira.jupiter.agent.tools.impl;

import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.llm.dto.ToolSchema;
import com.judepereira.jupiter.agent.tools.AgentTool;
import com.judepereira.jupiter.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter.agent.tools.ToolExecutionResult;
import lombok.val;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static com.judepereira.jupiter.agent.llm.dto.ToolParameter.integer;
import static com.judepereira.jupiter.agent.llm.dto.ToolParameter.string;

public class ReadFileTool implements AgentTool {
    private static final ToolDefinition DEF = ToolDefinition.builtIn(
            "read_file",
            "Read a file from the workspace (utf-8) with optional line range",
            ToolSchema.object(
                    string("path", "relative file path to read"),
                    integer("startLine", "optional 1-based start line"),
                    integer("endLine", "optional 1-based end line")
            ).required("path")
    );

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

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
        Integer start = args.get("startLine") instanceof Number ? ((Number) args.get("startLine")).intValue() : null;
        Integer end = args.get("endLine") instanceof Number ? ((Number) args.get("endLine")).intValue() : null;
        String text;
        if (start != null || end != null) {
            int skipped = 0;
            int read = 0;

            if (start != null && end != null && start > end) {
                return new ToolExecutionResult(false, "Invalid line range: start > end", Map.of());
            }

            val out = new StringBuilder();

            try (val br = new BufferedReader(new InputStreamReader(Files.newInputStream(p), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    read++;
                    if (start != null && ++skipped < start) {
                        continue;
                    }

                    if (end != null && read > end) {
                        break;
                    }

                    out.append(line);
                    out.append(System.lineSeparator());
                }

                text = out.toString();
            }
        } else {
            text = FileUtils.readUtf8(p, 1_000_000); // ~ 1 MB max - truncated by ToolRegistry later.
        }
        return new ToolExecutionResult(true, text, Map.of("path", rel));
    }
}
