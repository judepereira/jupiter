package com.judepereira.jupiter.agent.tools.impl;

import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.llm.dto.ToolSchema;
import com.judepereira.jupiter.agent.tools.AgentTool;
import com.judepereira.jupiter.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter.agent.tools.ToolExecutionResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static com.judepereira.jupiter.agent.llm.dto.ToolParameter.string;

public class DisplayImageTool implements AgentTool {
    private static final ToolDefinition DEF = new ToolDefinition(
            "display_image",
            "Display an image from the workspace",
            ToolSchema.object(
                    string("path", "workspace-relative image path"),
                    string("alt", "optional alt text")
            ).required("path")
    );

    @Override
    public String name() {
        return "display_image";
    }

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext context) throws Exception {
        String rel = (String) args.get("path");
        if (rel == null || rel.isBlank()) {
            return new ToolExecutionResult(false, "missing path", Map.of());
        }

        Path path = FileUtils.resolveWorkspacePath(context.getWorkspaceRoot(), rel);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return new ToolExecutionResult(false, "file not found: " + rel, Map.of());
        }

        path = FileUtils.ensureWorkspaceContained(context.getWorkspaceRoot(), path);

        String mediaType = FileUtils.resolveAllowedImageMediaType(Files.probeContentType(path), rel);
        if (mediaType == null) {
            return new ToolExecutionResult(false, "unsupported image type: " + rel, Map.of());
        }

        long sizeBytes = Files.size(path);
        String alt = (String) args.get("alt");
        return new ToolExecutionResult(true, "Displayed image: " + rel, Map.of(
                "displayType", "image",
                "path", rel,
                "alt", alt,
                "mediaType", mediaType,
                "sizeBytes", sizeBytes
        ));
    }
}
