package com.judepereira.jupiter.agent.tools.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FileUtils {
    public static Path resolveWorkspacePath(Path workspaceRoot, String relative) throws IOException {
        // handle null/blank relative as empty path
        String rel = relative == null || relative.isBlank() ? "" : relative;
        // canonicalize workspace root to absolute normalized path first
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path candidate = root.resolve(rel).normalize();
        if (!candidate.startsWith(root)) {
            throw new IOException("Path traversal outside workspace is not allowed: " + relative);
        }
        return candidate;
    }

    public static Path canonicalWorkspaceRoot(Path workspaceRoot) {
        return workspaceRoot.toAbsolutePath().normalize();
    }

    public static Path relativizeWorkspacePath(Path workspaceRoot, Path absolutePath) {
        Path root = canonicalWorkspaceRoot(workspaceRoot);
        Path p = absolutePath.toAbsolutePath().normalize();
        return root.relativize(p).normalize();
    }

    public static String readUtf8(Path path, int maxChars) throws IOException {
        byte[] all = Files.readAllBytes(path);
        String s = new String(all, java.nio.charset.StandardCharsets.UTF_8);
        if (maxChars > 0 && s.length() > maxChars) {
            return s.substring(0, maxChars);
        }
        return s;
    }
}
