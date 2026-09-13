package com.judepereira.jupiter.agent.tools.impl;

import com.judepereira.jupiter.agent.harness.StreamCancelledException;
import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.llm.dto.ToolSchema;
import com.judepereira.jupiter.agent.tools.AgentTool;
import com.judepereira.jupiter.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter.agent.tools.ToolExecutionResult;
import com.judepereira.jupiter.security.ProcessEnvironmentSanitizer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.judepereira.jupiter.agent.llm.dto.ToolParameter.string;

public class RunCommandTool implements AgentTool {
    private static final int INLINE_OUTPUT_LIMIT_BYTES = 4 * 1024;
    private static final int PREVIEW_EDGE_BYTES = 2 * 1024;
    private static final ToolDefinition DEF = ToolDefinition.builtIn(
            "run_command",
            "Run a shell command in workspace (restricted)",
            ToolSchema.object(
                    string("command", "shell command to run"),
                    string("workingDir", "optional relative working directory")
            ).required("command")
    );

    private final List<String> forbidden = List.of("rm -rf /", "shutdown", "reboot", "mkfs", ":(){ :|:& };:");
    private final TempFileFactory tempFileFactory;

    public RunCommandTool(TempFileFactory tempFileFactory) {
        this.tempFileFactory = tempFileFactory;
    }

    @Override
    public String name() {
        return "run_command";
    }

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext context) throws Exception {
        if (!context.isAllowCommand()) {
            return new ToolExecutionResult(false, "command execution disabled by configuration", Map.of());
        }
        String cmd = (String) args.get("command");
        String working = (String) args.getOrDefault("workingDir", "");
        if (cmd == null) {
            return new ToolExecutionResult(false, "missing command", Map.of());
        }
        for (String forbiddenCommand : forbidden) {
            if (cmd.contains(forbiddenCommand)) {
                return new ToolExecutionResult(false, "command denied by safety policy", Map.of());
            }
        }

        Path commandScript = null;
        OutputCapture stdoutCapture = null;
        OutputCapture stderrCapture = null;
        Process process = null;
        Thread stdoutThread = null;
        Thread stderrThread = null;
        Set<Path> retained = new HashSet<>();
        try {
            Path workingDirectory = FileUtils.resolveWorkspacePath(context.getWorkspaceRoot(), working);
            commandScript = tempFileFactory.create("jupiter-command", ".sh");
            Files.writeString(commandScript, cmd, StandardCharsets.UTF_8);
            ProcessBuilder processBuilder = new ProcessBuilder("/bin/sh", commandScript.toString());
            processBuilder.directory(workingDirectory.toFile());
            Map<String, String> environment = processBuilder.environment();
            environment.clear();
            environment.putAll(buildCommandEnvironment(
                    System.getenv(), context.getCommandEnvironmentAllowlist(), context.getEnvironmentVariables()));
            environment.put("LANG", "C.utf8");
            environment.put("LC_ALL", "C.utf8");
            // Create both destinations before launching the child. If allocation fails, no child
            // exists that could outlive this method while its pipes are not being drained.
            stdoutCapture = new OutputCapture("stdout", tempFileFactory);
            stderrCapture = new OutputCapture("stderr", tempFileFactory);
            process = processBuilder.start();
            OutputCapture stdout = stdoutCapture;
            OutputCapture stderr = stderrCapture;
            Process startedProcess = process;
            stdoutThread = new Thread(() -> capture(startedProcess.getInputStream(), stdout),
                    "run-command-stdout-" + startedProcess.pid());
            stderrThread = new Thread(() -> capture(startedProcess.getErrorStream(), stderr),
                    "run-command-stderr-" + startedProcess.pid());
            stdoutThread.start();
            stderrThread.start();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(context.getCommandTimeoutSeconds());
            boolean finished = false;
            while (!finished) {
                if (isCancelled(context)) {
                    stop(process, stdoutThread, stderrThread);
                    throw new StreamCancelledException();
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                finished = process.waitFor(
                        Math.max(1L, Math.min(TimeUnit.NANOSECONDS.toMillis(remaining), 100L)),
                        TimeUnit.MILLISECONDS);
            }
            if (!finished) {
                stop(process, stdoutThread, stderrThread);
                return new ToolExecutionResult(false, "command timed out", Map.of());
            }
            join(stdoutThread);
            join(stderrThread);
            if (stdoutCapture.failure() != null) throw stdoutCapture.failure();
            if (stderrCapture.failure() != null) throw stderrCapture.failure();
            if (isCancelled(context)) {
                throw new StreamCancelledException();
            }

            int exitCode = process.exitValue();
            String stdoutText = formatOutput(stdoutCapture);
            String stderrText = formatOutput(stderrCapture);
            if (stdoutCapture.isLarge()) {
                retained.add(stdoutCapture.path);
            }
            if (stderrCapture.isLarge()) {
                retained.add(stderrCapture.path);
            }
            Map<String, Object> machine = Map.of("exitCode", exitCode, "stdout", stdoutText, "stderr", stderrText);
            return new ToolExecutionResult(exitCode == 0, "exitCode=" + exitCode + "\n" + stdoutText + stderrText, machine);
        } finally {
            if (process != null && (process.isAlive() || isAlive(stdoutThread) || isAlive(stderrThread))) {
                stop(process, stdoutThread, stderrThread);
            }
            deleteIfUnretained(commandScript, retained);
            if (stdoutCapture != null) {
                deleteIfUnretained(stdoutCapture.path, retained);
            }
            if (stderrCapture != null) {
                deleteIfUnretained(stderrCapture.path, retained);
            }
        }
    }

    private static boolean isCancelled(ToolExecutionContext context) {
        return context.getCancellationToken() != null && context.getCancellationToken().isCancelled();
    }

    private static void stop(Process process, Thread stdoutThread, Thread stderrThread) {
        process.destroyForcibly();
        close(process.getInputStream());
        close(process.getErrorStream());
        joinUninterruptibly(stdoutThread);
        joinUninterruptibly(stderrThread);
    }

    private static void join(Thread thread) throws InterruptedException {
        if (thread != null) {
            thread.join();
        }
    }

    private static boolean isAlive(Thread thread) {
        return thread != null && thread.isAlive();
    }

    private static void joinUninterruptibly(Thread thread) {
        if (thread == null) {
            return;
        }
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void close(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // The capture thread records its own failure; shutdown preserves the primary outcome.
        }
    }

    private static void deleteIfUnretained(Path path, Set<Path> retained) {
        if (path != null && !retained.contains(path)) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Cleanup must not replace the command result or exception.
            }
        }
    }

    static Map<String, String> buildCommandEnvironment(Map<String, String> hostEnvironment,
                                                        Set<String> allowlist,
                                                        Map<String, String> projectEnvironment) {
        Map<String, String> environment = new java.util.HashMap<>();
        for (String name : allowlist) {
            String value = hostEnvironment.get(name);
            if (value != null) {
                environment.put(name, value);
            }
        }
        environment.putAll(projectEnvironment);
        ProcessEnvironmentSanitizer.sanitize(environment);
        return Map.copyOf(environment);
    }

    private static void capture(InputStream input, OutputCapture capture) {
        try (input; OutputStream output = Files.newOutputStream(capture.path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                capture.accept(buffer, read);
            }
            capture.finish(output);
        } catch (IOException e) {
            capture.failure = e;
        }
    }

    private String formatOutput(OutputCapture capture) throws Exception {
        if (!capture.isLarge()) {
            return Files.readString(capture.path, StandardCharsets.UTF_8);
        }
        return utf8Prefix(capture.prefix.toByteArray(), PREVIEW_EDGE_BYTES)
                + "\n...\n...\n"
                + utf8Suffix(capture.suffixBytes(), PREVIEW_EDGE_BYTES)
                + "\n\n"
                + capture.path;
    }

    @FunctionalInterface
    public interface TempFileFactory {
        Path create(String prefix, String suffix) throws IOException;
    }

    private static final class OutputCapture {
        private final Path path;
        private final ByteArrayOutputStream prefix = new ByteArrayOutputStream(PREVIEW_EDGE_BYTES);
        private final byte[] suffix = new byte[PREVIEW_EDGE_BYTES];
        private int suffixStart;
        private int suffixLength;
        private long size;
        private IOException failure;

        private OutputCapture(String streamName, TempFileFactory tempFileFactory) throws IOException {
            path = tempFileFactory.create(streamName, ".capture");
        }

        private boolean isLarge() {
            return size > INLINE_OUTPUT_LIMIT_BYTES;
        }

        private void accept(byte[] buffer, int length) throws IOException {
            size += length;
            prefix.write(buffer, 0, Math.min(length, PREVIEW_EDGE_BYTES - prefix.size()));
            int copied = Math.min(length, suffix.length);
            int end = (suffixStart + suffixLength) % suffix.length;
            int first = Math.min(copied, suffix.length - end);
            System.arraycopy(buffer, length - copied, suffix, end, first);
            if (copied > first) {
                System.arraycopy(buffer, length - copied + first, suffix, 0, copied - first);
            }
            if (suffixLength + copied <= suffix.length) {
                suffixLength += copied;
            } else {
                suffixStart = (suffixStart + suffixLength + copied - suffix.length) % suffix.length;
                suffixLength = suffix.length;
            }
        }

        private IOException failure() {
            return failure;
        }

        private void finish(OutputStream output) throws IOException {
            if (size > 0 && suffixBytes()[suffixLength - 1] != '\n') {
                output.write('\n');
                accept(new byte[]{'\n'}, 1);
            }
        }

        private byte[] suffixBytes() {
            byte[] result = new byte[suffixLength];
            int first = Math.min(suffixLength, suffix.length - suffixStart);
            System.arraycopy(suffix, suffixStart, result, 0, first);
            if (suffixLength > first) {
                System.arraycopy(suffix, 0, result, first, suffixLength - first);
            }
            return result;
        }
    }

    private static String utf8Prefix(byte[] bytes, int maxBytes) throws CharacterCodingException {
        int length = Math.min(maxBytes, bytes.length);
        while (length > 0) {
            try {
                return decodeUtf8(bytes, 0, length);
            } catch (CharacterCodingException e) {
                length--;
            }
        }
        return "";
    }

    private static String utf8Suffix(byte[] bytes, int maxBytes) throws CharacterCodingException {
        int start = Math.max(0, bytes.length - maxBytes);
        while (start < bytes.length) {
            try {
                return decodeUtf8(bytes, start, bytes.length - start);
            } catch (CharacterCodingException e) {
                start++;
            }
        }
        return "";
    }

    private static String decodeUtf8(byte[] bytes, int offset, int length) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
                .toString();
    }
}
