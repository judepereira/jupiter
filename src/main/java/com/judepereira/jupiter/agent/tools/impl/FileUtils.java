package com.judepereira.jupiter.agent.tools.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileUtils {
    public static Path resolveWorkspacePath(Path workspaceRoot, String relative) throws IOException {
        // handle null/blank relative as empty path
        String rel = relative == null || relative.isBlank() ? "" : relative;
        // canonicalize workspace root to absolute normalized path first
        Path root = workspaceRoot.toAbsolutePath().normalize();
        return root.resolve(rel).normalize();
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

    public static String resolveAllowedImageMediaType(String mediaType, String relativePath) {
        if (isAllowedImageMediaType(mediaType)) {
            return mediaType;
        }
        return imageMediaTypeFromExtension(relativePath);
    }

    private static boolean isAllowedImageMediaType(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return false;
        }
        return switch (mediaType) {
            case "image/png", "image/jpeg", "image/gif", "image/webp" -> true;
            default -> false;
        };
    }

    private static String imageMediaTypeFromExtension(String relativePath) {
        if (relativePath == null) {
            return null;
        }
        String lower = relativePath.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return null;
    }
}
