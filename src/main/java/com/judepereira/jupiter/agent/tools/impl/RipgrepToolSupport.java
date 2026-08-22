package com.judepereira.jupiter.agent.tools.impl;

import com.judepereira.jupiter.agent.tools.ToolExecutionResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class RipgrepToolSupport {
    private static final String RG = "rg";
    private static final int BOOT_CHECK_TIMEOUT_SECONDS = 5;

    public void assertAvailable() {
        RunResult result = run(List.of(RG, "--version"), null, BOOT_CHECK_TIMEOUT_SECONDS);
        if (result.missingCommand()) {
            throw new IllegalStateException("ripgrep (rg) is not available on PATH", result.failure());
        }
        if (result.timedOut()) {
            throw new IllegalStateException("ripgrep availability check timed out", result.failure());
        }
        if (result.exitCode() != 0) {
            String message = "ripgrep availability check failed with exit code " + result.exitCode();
            if (!result.stderr().isBlank()) {
                message += ": " + result.stderr().trim();
            } else if (!result.stdout().isBlank()) {
                message += ": " + result.stdout().trim();
            }
            throw new IllegalStateException(message, result.failure());
        }
    }

    public ToolExecutionResult listFiles(Path workspaceRoot, String relativePath, String include, int timeoutSeconds) {
        Path resolvedRoot;
        try {
            resolvedRoot = resolveWorkspacePath(workspaceRoot, relativePath);
        } catch (IOException e) {
            return new ToolExecutionResult(false, "failed to resolve path: " + normalizeRelativePath(relativePath), Map.of());
        }
        if (!Files.exists(resolvedRoot)) {
            return new ToolExecutionResult(false, "path does not exist: " + normalizeRelativePath(relativePath), Map.of());
        }

        List<String> command = new ArrayList<>();
        command.add(RG);
        command.add("--files");
        addInclude(command, include);
        command.add("--");
        if (relativePath != null && !relativePath.isBlank()) {
            String normalizedPath = normalizeRelativePath(relativePath);
            if (!".".equals(normalizedPath)) {
                command.add(normalizedPath);
            }
        }

        RunResult result = run(command, FileUtils.canonicalWorkspaceRoot(workspaceRoot), timeoutSeconds);
        if (result.missingCommand()) {
            return failure("ripgrep (rg) is not available on PATH");
        }
        if (result.timedOut()) {
            return failure("ripgrep command timed out");
        }
        if (result.exitCode() != 0 && result.exitCode() != 1) {
            return failure(rgFailureMessage(result));
        }

        List<String> files = normalizeOutput(splitLines(result.stdout()));
        String text = String.join("\n", files);
        return new ToolExecutionResult(true, text, Map.of("files", files));
    }

    public ToolExecutionResult searchCode(Path workspaceRoot, String relativePath, String pattern, String include, int timeoutSeconds) {
        if (pattern == null || pattern.isBlank()) {
            return new ToolExecutionResult(false, "pattern is required", Map.of());
        }

        Path resolvedRoot;
        try {
            resolvedRoot = resolveWorkspacePath(workspaceRoot, relativePath);
        } catch (IOException e) {
            return new ToolExecutionResult(false, "failed to resolve path: " + normalizeRelativePath(relativePath), Map.of());
        }
        if (!Files.exists(resolvedRoot)) {
            return new ToolExecutionResult(false, "path does not exist: " + normalizeRelativePath(relativePath), Map.of());
        }

        List<String> command = new ArrayList<>();
        command.add(RG);
        command.add("--no-heading");
        command.add("--line-number");
        command.add("--with-filename");
        command.add("--color");
        command.add("never");
        addInclude(command, include);
        command.add("--");
        command.add(pattern);
        if (relativePath == null || relativePath.isBlank()) {
            // rg reads from stdin when no path is provided; pin it to the workspace root instead.
            command.add(".");
        } else {
            command.add(normalizeRelativePath(relativePath));
        }

        RunResult result = run(command, FileUtils.canonicalWorkspaceRoot(workspaceRoot), timeoutSeconds);
        if (result.missingCommand()) {
            return failure("ripgrep (rg) is not available on PATH");
        }
        if (result.timedOut()) {
            return failure("ripgrep command timed out");
        }
        if (result.exitCode() != 0 && result.exitCode() != 1) {
            return failure(rgFailureMessage(result));
        }

        List<String> matches = normalizeOutput(splitLines(result.stdout()));
        String text = String.join("\n", matches);
        return new ToolExecutionResult(true, text, Map.of("matches", matches));
    }

    private static void addInclude(List<String> command, String include) {
        if (include != null && !include.isBlank()) {
            command.add("-g");
            command.add(include);
        }
    }

    private static Path resolveWorkspacePath(Path workspaceRoot, String relativePath) throws IOException {
        Path resolved = FileUtils.resolveWorkspacePath(workspaceRoot, relativePath);
        Path root = FileUtils.canonicalWorkspaceRoot(workspaceRoot);
        if (!resolved.startsWith(root)) {
            throw new IOException("path escapes workspace root");
        }
        return resolved;
    }

    private static String normalizeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return ".";
        }
        String normalized = Path.of(relativePath).normalize().toString();
        return normalized.isBlank() ? "." : normalized;
    }

    private static List<String> splitLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return text.lines().toList();
    }

    private static List<String> normalizeOutput(List<String> lines) {
        if (lines.isEmpty()) {
            return lines;
        }
        return lines.stream().map(RipgrepToolSupport::stripLeadingCurrentDirectory).toList();
    }

    private static String stripLeadingCurrentDirectory(String line) {
        return line.startsWith("./") ? line.substring(2) : line;
    }

    private static ToolExecutionResult failure(String message) {
        return new ToolExecutionResult(false, message, Map.of());
    }

    private static String rgFailureMessage(RunResult result) {
        String message = "ripgrep command failed with exit code " + result.exitCode();
        if (!result.stderr().isBlank()) {
            message += ": " + result.stderr().trim();
        } else if (!result.stdout().isBlank()) {
            message += ": " + result.stdout().trim();
        }
        return message;
    }

    private static RunResult run(List<String> command, Path workingDirectory, int timeoutSeconds) {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return RunResult.missingCommand(e);
        }

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread stdoutThread = new Thread(() -> readStream(process.getInputStream(), stdout));
        Thread stderrThread = new Thread(() -> readStream(process.getErrorStream(), stderr));
        stdoutThread.start();
        stderrThread.start();

        boolean finished;
        try {
            finished = process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            joinQuietly(stdoutThread);
            joinQuietly(stderrThread);
            return RunResult.timedOut(stdout.toString(), stderr.toString(), e);
        }

        if (!finished) {
            process.destroyForcibly();
            joinQuietly(stdoutThread);
            joinQuietly(stderrThread);
            return RunResult.timedOut(stdout.toString(), stderr.toString(), null);
        }

        joinQuietly(stdoutThread);
        joinQuietly(stderrThread);
        return RunResult.completed(process.exitValue(), stdout.toString(), stderr.toString());
    }

    private static void readStream(java.io.InputStream stream, StringBuilder output) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) {
                    output.append('\n');
                }
                output.append(line);
            }
        } catch (IOException ignored) {
            // ignore
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record RunResult(int exitCode, String stdout, String stderr, boolean timedOut, boolean missingCommand,
                             Throwable failure) {
        static RunResult completed(int exitCode, String stdout, String stderr) {
            return new RunResult(exitCode, stdout == null ? "" : stdout, stderr == null ? "" : stderr, false, false, null);
        }

        static RunResult timedOut(String stdout, String stderr, Throwable failure) {
            return new RunResult(-1, stdout == null ? "" : stdout, stderr == null ? "" : stderr, true, false, failure);
        }

        static RunResult missingCommand(Throwable failure) {
            return new RunResult(-1, "", "", false, true, failure);
        }
    }
}
