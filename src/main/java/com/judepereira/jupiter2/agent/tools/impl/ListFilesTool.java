package com.judepereira.jupiter2.agent.tools.impl;

import com.judepereira.jupiter2.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter2.agent.tools.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class ListFilesTool implements AgentTool {
    private final ToolDefinition def;

    public ListFilesTool() {
        Map<String, Object> schema = Map.of(
                "path", Map.of("type", "string", "description", "relative path to list"),
                "include", Map.of("type", "string", "description", "optional glob filter, e.g. **/*.java")
        );
        this.def = new ToolDefinition(name(), "List files under a relative path", schema);
    }

    @Override
    public String name() {
        return "list_files";
    }

    @Override
    public ToolDefinition definition() {
        return def;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext context) throws Exception {
        String rel = (String) args.getOrDefault("path", "");
        Path p = FileUtils.resolveWorkspacePath(context.getWorkspaceRoot(), rel);
        if (!Files.exists(p)) {
            return new ToolExecutionResult(false, "path does not exist: " + rel, Map.of());
        }
        String include = (String) args.getOrDefault("include", "");
        // matcher should operate on workspace-relative paths
        Path canonicalRoot = FileUtils.canonicalWorkspaceRoot(context.getWorkspaceRoot());
        final java.nio.file.PathMatcher matcher = (include != null && !include.isBlank()) ? canonicalRoot.getFileSystem().getPathMatcher("glob:" + include) : null;
        List<String> files = new ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> stream = Files.walk(p)) {
            stream.filter(Files::isRegularFile).forEach(pp -> {
                Path relPath = FileUtils.relativizeWorkspacePath(context.getWorkspaceRoot(), pp);
                if (matcher != null && !matcher.matches(relPath)) return;
                files.add(relPath.toString());
            });
        }
        String text = String.join("\n", files);
        return new ToolExecutionResult(true, text, Map.of("files", files));
    }
}
