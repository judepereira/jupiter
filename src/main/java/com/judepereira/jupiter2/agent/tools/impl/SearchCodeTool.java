package com.judepereira.jupiter2.agent.tools.impl;

import com.judepereira.jupiter2.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter2.agent.tools.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SearchCodeTool implements AgentTool {
    private static final ToolDefinition DEF = new ToolDefinition(
            "search_code",
            "Search regex across files in workspace",
            Map.of(
                    "path", Map.of("type", "string", "description", "relative path root to search"),
                    "pattern", Map.of("type", "string", "description", "regex pattern to search (required)"),
                    "include", Map.of("type", "string", "description", "optional glob include e.g. **/*.java")
            )
    );

    @Override
    public String name() { return "search_code"; }

    @Override
    public ToolDefinition definition() { return DEF; }

    @Override
    public ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext context) throws Exception {
        String rel = (String) args.getOrDefault("path", "");
        String pattern = (String) args.get("pattern");
        if (pattern == null) return new ToolExecutionResult(false, "pattern is required", Map.of());
        Path root = FileUtils.resolveWorkspacePath(context.getWorkspaceRoot(), rel);
        if (!Files.exists(root)) return new ToolExecutionResult(false, "path not found: " + rel, Map.of());
        Pattern p = Pattern.compile(pattern);
        String include = (String) args.getOrDefault("include", "");
        // matcher should operate on workspace-relative paths
        Path canonicalRoot = FileUtils.canonicalWorkspaceRoot(context.getWorkspaceRoot());
        final java.nio.file.PathMatcher matcher = (include != null && !include.isBlank()) ? canonicalRoot.getFileSystem().getPathMatcher("glob:" + include) : null;
        List<String> matches = new ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(fp -> {
                Path relPath = FileUtils.relativizeWorkspacePath(context.getWorkspaceRoot(), fp);
                if (matcher != null && !matcher.matches(relPath)) return;
                try (BufferedReader br = Files.newBufferedReader(fp)) {
                    String line;
                    int ln = 0;
                    while ((line = br.readLine()) != null) {
                        ln++;
                        Matcher m = p.matcher(line);
                        if (m.find()) {
                            matches.add(relPath + ":" + ln + ": " + line.trim());
                        }
                    }
                } catch (IOException e) {
                    // ignore
                }
            });
        }
        return new ToolExecutionResult(true, String.join("\n", matches), Map.of("matches", matches));
    }
}
